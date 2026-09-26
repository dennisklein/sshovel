// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package mobile

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/miekg/dns"
	"golang.org/x/crypto/ssh"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"

	"github.com/dennisklein/sshovel/core/internal/testutil"
)

type fakePlatform struct {
	key    *ecdsa.PrivateKey
	mu     sync.Mutex
	states []map[string]any
	logs   []string
	dns    []string
	cond   *sync.Cond
}

func newFakePlatform(t *testing.T) *fakePlatform {
	p := &fakePlatform{key: testutil.NewECDSAKey(t)}
	p.cond = sync.NewCond(&p.mu)
	return p
}

func (p *fakePlatform) Protect(fd int32) bool { return fd > 0 }
func (p *fakePlatform) SignDigest(alias string, d []byte) ([]byte, error) {
	if alias != "k1" {
		return nil, errors.New("no key")
	}
	return ecdsa.SignASN1(rand.Reader, p.key, d)
}
func (p *fakePlatform) QueryUpstreamDNS(q []byte) ([]byte, error) {
	m := new(dns.Msg)
	if err := m.Unpack(q); err != nil {
		return nil, err
	}
	r := new(dns.Msg)
	r.SetReply(m)
	rr, _ := dns.NewRR(m.Question[0].Name + " 60 IN A 127.0.0.1")
	r.Answer = append(r.Answer, rr)
	return r.Pack()
}
func (p *fakePlatform) OnState(s string) {
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		panic(err)
	}
	p.mu.Lock()
	p.states = append(p.states, m)
	p.mu.Unlock()
	p.cond.Broadcast()
}
func (p *fakePlatform) Log(level int32, comp, msg string) {
	p.mu.Lock()
	p.logs = append(p.logs, fmt.Sprintf("%d %s %s", level, comp, msg))
	p.mu.Unlock()
}
func (p *fakePlatform) OnDnsEvent(e string) {
	p.mu.Lock()
	p.dns = append(p.dns, e)
	p.mu.Unlock()
}
func (p *fakePlatform) OnFlowEvent(string) {}

func (p *fakePlatform) waitState(t *testing.T, want string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	go func() { time.Sleep(10 * time.Second); p.cond.Broadcast() }()
	p.mu.Lock()
	defer p.mu.Unlock()
	for {
		for _, s := range p.states {
			if s["state"] == want {
				return
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("no state %q in %v", want, p.states)
		}
		p.cond.Wait()
	}
}

func profileJSON(t *testing.T, p *fakePlatform, srv *testutil.SSHServer, pinned bool) string {
	pkix, _ := x509.MarshalPKIXPublicKey(&p.key.PublicKey)
	hk := ""
	if pinned {
		k := srv.HostSigner.PublicKey()
		hk = fmt.Sprintf(`"hostKey": {"type": %q, "fingerprint": %q},`, k.Type(), ssh.FingerprintSHA256(k))
	}
	// Host is a name: resolving it exercises QueryUpstreamDNS.
	return fmt.Sprintf(`{
  "name": "Test", "server": {"host": "jump.example.test", "port": %d, "user": "tester"},
  "auth": {"kind": "keystore", "alias": "k1", "publicKeyPkix": %q}, %s
  "routes": ["10.77.0.0/24"],
  "dns": {"server": "10.77.0.53", "suffixes": ["corp.test"]}
}`, srv.Port, base64.StdEncoding.EncodeToString(pkix), hk)
}

func newServer(t *testing.T, p *fakePlatform) *testutil.SSHServer {
	pub, _ := ssh.NewPublicKey(&p.key.PublicKey)
	return testutil.NewSSHServer(t, pub)
}

func TestEngineLifecycleWithTunFd(t *testing.T) {
	p := newFakePlatform(t)
	srv := newServer(t, p)
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	phone := fds[1] // the kernel side of our fake TUN
	defer unix.Close(phone)

	e := NewEngine(p)
	if err := e.Start(-1, profileJSON(t, p, srv, true), nil); err != nil {
		t.Fatal(err)
	}
	p.waitState(t, "sshReady")
	if err := e.AttachTun(int32(fds[0])); err != nil {
		t.Fatal(err)
	}
	p.waitState(t, "on")

	// A direct DNS query written into the "TUN" comes back answered.
	q := new(dns.Msg)
	q.SetQuestion("example.com.", dns.TypeA)
	q.Id = 99
	raw, _ := q.Pack()
	if _, err := unix.Write(phone, udpPacket(netip.MustParseAddrPort("198.18.0.1:40000"), netip.MustParseAddrPort("198.18.0.53:53"), raw)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2048)
	_ = unix.SetsockoptTimeval(phone, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Sec: 5})
	n, err := unix.Read(phone, buf)
	if err != nil {
		t.Fatal(err)
	}
	ip := header.IPv4(buf[:n])
	udp := header.UDP(ip.Payload())
	r := new(dns.Msg)
	if err := r.Unpack(udp.Payload()); err != nil || r.Id != 99 || len(r.Answer) != 1 {
		t.Fatalf("DNS reply %v, %v", r, err)
	}

	var stats map[string]any
	if err := json.Unmarshal([]byte(e.StatsJSON()), &stats); err != nil || stats["dnsDirect"] != float64(1) {
		t.Errorf("stats %s", e.StatsJSON())
	}
	for _, k := range []string{"uptimeSec", "bytesIn", "bytesOut", "activeFlows", "dnsTunneled", "dnsDirect", "droppedUdp", "droppedIcmp", "lastError"} {
		if _, ok := stats[k]; !ok {
			t.Errorf("stats missing %s", k)
		}
	}

	e.Stop()
	p.waitState(t, "off")
	// Go closed its end of the TUN.
	if n, err := unix.Read(phone, buf); n != 0 || err != nil {
		t.Errorf("TUN still open after Stop: n=%d err=%v", n, err)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.dns) != 1 || !strings.Contains(p.dns[0], `"route":"direct"`) {
		t.Errorf("dns events %v", p.dns)
	}
}

func udpPacket(src, dst netip.AddrPort, payload []byte) []byte {
	b := make([]byte, header.IPv4MinimumSize+header.UDPMinimumSize+len(payload))
	ip := header.IPv4(b)
	ip.Encode(&header.IPv4Fields{TotalLength: uint16(len(b)), TTL: 64, Protocol: uint8(header.UDPProtocolNumber),
		SrcAddr: tcpip.AddrFrom4(src.Addr().As4()), DstAddr: tcpip.AddrFrom4(dst.Addr().As4())})
	ip.SetChecksum(^ip.CalculateChecksum())
	u := header.UDP(b[header.IPv4MinimumSize:])
	u.Encode(&header.UDPFields{SrcPort: src.Port(), DstPort: dst.Port(), Length: uint16(header.UDPMinimumSize + len(payload))})
	copy(u.Payload(), payload)
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, ip.SourceAddress(), ip.DestinationAddress(), u.Length())
	xsum = checksum.Checksum(payload, xsum)
	u.SetChecksum(^u.CalculateChecksum(xsum))
	return b
}

func TestStartErrors(t *testing.T) {
	p := newFakePlatform(t)
	srv := newServer(t, p)
	e := NewEngine(p)
	if err := e.Start(-1, `{"server":{}}`, nil); err == nil || !strings.HasPrefix(err.Error(), "INTERNAL: invalid profile: server.host=REQUIRED") {
		t.Errorf("invalid profile: %v", err)
	}
	if err := e.Start(-1, profileJSON(t, p, srv, false), nil); err != nil {
		t.Fatal(err)
	}
	defer e.Stop()
	p.waitState(t, "needsAttention")
	p.mu.Lock()
	last := p.states[len(p.states)-1]
	p.mu.Unlock()
	if last["code"] != "HOST_KEY_UNVERIFIED" {
		t.Errorf("state %v", last)
	}
	if err := e.Start(-1, profileJSON(t, p, srv, false), nil); err == nil {
		t.Error("second Start succeeded")
	}
}

func TestImportedKeyZeroed(t *testing.T) {
	p := newFakePlatform(t)
	srv := newServer(t, p)
	cfg := strings.Replace(profileJSON(t, p, srv, true), `"kind": "keystore"`, `"kind": "imported"`, 1)
	key := []byte("-----BEGIN OPENSSH PRIVATE KEY-----\nnot really\n")
	e := NewEngine(p)
	err := e.Start(-1, cfg, key)
	if err == nil || !strings.HasPrefix(err.Error(), "KEY_UNAVAILABLE") {
		t.Errorf("got %v", err)
	}
	for _, b := range key {
		if b != 0 {
			t.Fatal("imported key not zeroed")
		}
	}
}

func TestFetchHostKeyAndDiscoverRoutes(t *testing.T) {
	p := newFakePlatform(t)
	srv := newServer(t, p)
	srv.Exec["ip -4 route show"] = "10.77.0.0/24 dev eth1 scope link\ndefault via 172.18.0.1 dev eth0\n"

	out, err := FetchHostKey(p, profileJSON(t, p, srv, false))
	if err != nil {
		t.Fatal(err)
	}
	var hk struct{ Type, Fingerprint, Line string }
	if err := json.Unmarshal([]byte(out), &hk); err != nil || hk.Fingerprint != ssh.FingerprintSHA256(srv.HostSigner.PublicKey()) || hk.Line == "" {
		t.Errorf("host key %s", out)
	}

	if _, err := DiscoverRoutes(p, profileJSON(t, p, srv, false), nil); err == nil || !strings.HasPrefix(err.Error(), "HOST_KEY_UNVERIFIED") {
		t.Errorf("discover without pin: %v", err)
	}
	out, err = DiscoverRoutes(p, profileJSON(t, p, srv, true), nil)
	if err != nil {
		t.Fatal(err)
	}
	if out != `[{"cidr":"0.0.0.0/0","dev":"eth0","isDefault":true,"isLinkLocal":false},{"cidr":"10.77.0.0/24","dev":"eth1","isDefault":false,"isLinkLocal":false}]` {
		t.Errorf("routes %s", out)
	}
	srv.DenyExec.Store(true)
	if _, err := DiscoverRoutes(p, profileJSON(t, p, srv, true), nil); err == nil || !strings.HasPrefix(err.Error(), "ROUTE_DISCOVERY_UNAVAILABLE") {
		t.Errorf("exec denied: %v", err)
	}
}

func TestImportKey(t *testing.T) {
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	block, err := ssh.MarshalPrivateKeyWithPassphrase(priv, "", []byte("pw"))
	if err != nil {
		t.Fatal(err)
	}
	in, pass := pem.EncodeToMemory(block), []byte("pw")
	k, err := ImportKey(in, pass, "phone")
	if err != nil {
		t.Fatal(err)
	}
	if !allZero(in) || !allZero(pass) {
		t.Error("inputs not zeroed")
	}
	if k.Type != ssh.KeyAlgoED25519 || !strings.HasSuffix(k.AuthorizedLine, " phone") {
		t.Errorf("got %+v", k)
	}
	k.Clear()
	if !allZero(k.Key) {
		t.Error("Clear left key bytes")
	}
	if _, err := ImportKey([]byte("x"), nil, ""); err == nil || !strings.HasPrefix(err.Error(), "KEY_UNSUPPORTED: ") {
		t.Errorf("garbage: %v", err)
	}
}

func allZero(b []byte) bool {
	for _, c := range b {
		if c != 0 {
			return false
		}
	}
	return true
}

func TestValidateConfig(t *testing.T) {
	if got := ValidateConfig("{"); !strings.Contains(got, "INVALID_JSON") {
		t.Errorf("bad JSON: %s", got)
	}
	got := ValidateConfig(`{"server":{"host":"h","user":"u"},"auth":{"kind":"imported","alias":"a"},"routes":["10.0.0.0/8","10.1.0.0/16"]}`)
	if got != `[{"field":"routes[1]","code":"ROUTE_OVERLAP","severity":"warning","suggestion":"10.0.0.0/8"}]` {
		t.Errorf("got %s", got)
	}
	if got := ValidateConfig(`{"server":{"host":"h","user":"u"},"auth":{"kind":"imported","alias":"a"},"routes":["10.0.0.0/8"]}`); got != "" {
		t.Errorf("valid profile: %s", got)
	}
}

func TestVersion(t *testing.T) {
	if !strings.HasPrefix(Version(), "dev (go1.") {
		t.Errorf("version %q", Version())
	}
}
