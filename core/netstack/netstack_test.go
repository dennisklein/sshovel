// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package netstack_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/netstack"
	"github.com/dennisklein/sshovel/core/sshx"
)

var (
	tunPrefix = netip.MustParsePrefix("198.18.0.0/24")
	tunAddr   = netip.MustParseAddr("198.18.0.1")
	dnsVIP    = netip.MustParseAddr("198.18.0.53")
	wiki      = netip.MustParseAddrPort("10.77.0.20:7")
	closed    = netip.MustParseAddrPort("10.77.0.21:9")
)

type env struct {
	peer  *testutil.Peer
	ns    *netstack.Stack
	srv   *testutil.SSHServer
	flows chan netstack.FlowEvent
}

type fakeDNS struct{ tcp atomic.Int64 }

func (f *fakeDNS) HandleUDP(_ context.Context, q []byte) []byte { return append([]byte("re:"), q...) }
func (f *fakeDNS) ServeTCP(_ context.Context, c net.Conn) {
	f.tcp.Add(1)
	_, _ = io.Copy(c, c)
}

// newEnv wires peer (apps) ⇄ netstack ⇄ SSH client ⇄ in-process sshd, which
// maps the "intranet" addresses to local listeners.
func newEnv(t *testing.T) *env {
	t.Helper()
	key := testutil.NewECDSAKey(t)
	signer, _ := ssh.NewSignerFromKey(key)
	srv := testutil.NewSSHServer(t, signer.PublicKey())
	echo := testutil.EchoServer(t)
	srv.Dial = func(addr string) (net.Conn, error) {
		switch addr {
		case wiki.String():
			return net.Dial("tcp", echo.Addr().String())
		default:
			return nil, errors.New("connection refused")
		}
	}
	c, err := sshx.Dial(context.Background(), sshx.Options{Host: "127.0.0.1", Port: srv.Port, User: "tester",
		Signer: signer, Timeout: 5 * time.Second,
		Pin: &config.HostKey{Type: srv.HostSigner.PublicKey().Type(), Fingerprint: ssh.FingerprintSHA256(srv.HostSigner.PublicKey())}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })

	peer := testutil.NewPeer(t, tunAddr, 1500)
	e := &env{peer: peer, srv: srv, flows: make(chan netstack.FlowEvent, 1000)}
	ns, err := netstack.New(peer.Link, netstack.Options{
		MTU: 1500, TunPrefix: tunPrefix, DNSVirtualIP: dnsVIP, ConnectTimeout: 2 * time.Second,
		Dial: c.DialTCP, DNS: &fakeDNS{},
		OnFlow: func(ev netstack.FlowEvent) {
			select {
			case e.flows <- ev:
			default:
			}
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(ns.Close)
	e.ns = ns
	return e
}

func (e *env) dial(t *testing.T, dst netip.AddrPort) net.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := e.peer.DialTCP(ctx, dst)
	if err != nil {
		t.Fatalf("dial %s: %v", dst, err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func expectRST(t *testing.T, e *env, dst netip.AddrPort) time.Duration {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	c, err := e.peer.DialTCP(ctx, dst)
	if err == nil {
		c.Close()
		t.Fatalf("dial %s succeeded, want RST", dst)
	}
	if !bytes.Contains([]byte(err.Error()), []byte("refused")) {
		t.Fatalf("dial %s: %v, want connection refused (RST)", dst, err)
	}
	return time.Since(start)
}

// waitStats polls: the relay counts bytes after the app may already have
// read them.
func (e *env) waitStats(t *testing.T, ok func(netstack.Stats) bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !ok(e.ns.Stats()) {
		if time.Now().After(deadline) {
			t.Fatalf("stats %+v", e.ns.Stats())
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func (e *env) waitFlow(t *testing.T, kind string) netstack.FlowEvent {
	t.Helper()
	timeout := time.After(5 * time.Second)
	for {
		select {
		case ev := <-e.flows:
			if ev.Event == kind {
				return ev
			}
		case <-timeout:
			t.Fatalf("no %s flow event", kind)
		}
	}
}

func TestEcho(t *testing.T) {
	e := newEnv(t)
	c := e.dial(t, wiki)
	if ev := e.waitFlow(t, netstack.FlowOpen); ev.Dst != wiki.String() || ev.Src == "" {
		t.Errorf("open event %+v", ev)
	}
	msg := []byte("hello through the tunnel")
	if _, err := c.Write(msg); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil || !bytes.Equal(got, msg) {
		t.Fatalf("got %q, %v", got, err)
	}
	e.waitStats(t, func(st netstack.Stats) bool {
		return st.ActiveFlows == 1 && st.BytesIn == uint64(len(msg)) && st.BytesOut == uint64(len(msg))
	})
	c.Close()
	ev := e.waitFlow(t, netstack.FlowClose)
	if ev.BytesIn != uint64(len(msg)) || ev.BytesOut != uint64(len(msg)) {
		t.Errorf("close event %+v", ev)
	}
}

func TestRSTOnFailure(t *testing.T) {
	e := newEnv(t)
	t.Run("unreachable", func(t *testing.T) {
		expectRST(t, e, closed)
		if ev := e.waitFlow(t, netstack.FlowFail); ev.Reason != string(errcode.DestUnreachable) {
			t.Errorf("reason %q", ev.Reason)
		}
	})
	t.Run("prohibited", func(t *testing.T) {
		e.srv.ProhibitForwarding.Store(true)
		defer e.srv.ProhibitForwarding.Store(false)
		expectRST(t, e, wiki)
		if ev := e.waitFlow(t, netstack.FlowFail); ev.Reason != string(errcode.ForwardingDenied) {
			t.Errorf("reason %q", ev.Reason)
		}
	})
	t.Run("dns vip port 853", func(t *testing.T) {
		if d := expectRST(t, e, netip.AddrPortFrom(dnsVIP, 853)); d > time.Second {
			t.Errorf("RST took %s", d)
		}
	})
	t.Run("tun subnet", func(t *testing.T) {
		expectRST(t, e, netip.MustParseAddrPort("198.18.0.7:80"))
	})
	if e.ns.Stats().ActiveFlows != 0 {
		t.Error("failed flows counted as active")
	}
}

func TestDNSVirtualIP(t *testing.T) {
	e := newEnv(t)
	// TCP/53 goes to the local DNS handler (here: echo).
	c := e.dial(t, netip.AddrPortFrom(dnsVIP, 53))
	if _, err := c.Write([]byte("q")); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 1)
	if _, err := io.ReadFull(c, b); err != nil {
		t.Fatal(err)
	}
	// UDP/53 too.
	u, err := e.peer.DialUDP(netip.AddrPortFrom(dnsVIP, 53))
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	if _, err := u.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	_ = u.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 64)
	n, err := u.Read(buf)
	if err != nil || string(buf[:n]) != "re:abc" {
		t.Fatalf("udp got %q, %v", buf[:n], err)
	}
}

func TestDrops(t *testing.T) {
	e := newEnv(t)
	u, err := e.peer.DialUDP(netip.MustParseAddrPort("10.77.0.20:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	for range 3 {
		_, _ = u.Write([]byte("quic"))
	}
	// ICMP echo request 198.18.0.1 → 10.77.0.20.
	pkt := make([]byte, header.IPv4MinimumSize+header.ICMPv4MinimumSize)
	ip := header.IPv4(pkt)
	ip.Encode(&header.IPv4Fields{TotalLength: uint16(len(pkt)), TTL: 64, Protocol: uint8(header.ICMPv4ProtocolNumber),
		SrcAddr: tcpipAddr(tunAddr), DstAddr: tcpipAddr(wiki.Addr())})
	ip.SetChecksum(^ip.CalculateChecksum())
	icmp := header.ICMPv4(pkt[header.IPv4MinimumSize:])
	icmp.SetType(header.ICMPv4Echo)
	icmp.SetChecksum(^checksum.Checksum(icmp, 0))
	e.peer.InjectToEngine(pkt)

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		st := e.ns.Stats()
		if st.DroppedUDP == 3 && st.DroppedICMP == 1 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Errorf("stats %+v, want 3 UDP and 1 ICMP dropped", e.ns.Stats())
}

func TestHalfClose(t *testing.T) {
	e := newEnv(t)
	c := e.dial(t, wiki).(interface {
		net.Conn
		CloseWrite() error
	})
	msg := bytes.Repeat([]byte("x"), 100_000)
	if _, err := c.Write(msg); err != nil {
		t.Fatal(err)
	}
	// The app is done sending; the echo server sees EOF only if the FIN made
	// it through SSH, and replies with everything plus its own FIN.
	if err := c.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	got, err := io.ReadAll(c)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatalf("got %d bytes back, want %d", len(got), len(msg))
	}
}

func TestConcurrentFlows(t *testing.T) {
	e := newEnv(t)
	const n = 100
	var wg sync.WaitGroup
	errs := make(chan error, n)
	conns := make(chan net.Conn, n)
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			c, err := e.peer.DialTCP(ctx, wiki)
			if err != nil {
				errs <- err
				return
			}
			conns <- c
			msg := []byte(fmt.Sprintf("flow %03d ", i))
			msg = bytes.Repeat(msg, 1000)
			if _, err := c.Write(msg); err != nil {
				errs <- err
				return
			}
			got := make([]byte, len(msg))
			_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
			if _, err := io.ReadFull(c, got); err != nil {
				errs <- err
				return
			}
			if !bytes.Equal(got, msg) {
				errs <- fmt.Errorf("flow %d: data mismatch", i)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if got := e.ns.Stats().ActiveFlows; got != n {
		t.Errorf("active flows %d, want %d", got, n)
	}
	if got := e.srv.Channels.Load(); got != n {
		t.Errorf("SSH channels %d, want %d", got, n)
	}
	close(conns)
	for c := range conns {
		c.Close()
	}
}

func TestLargeTransfer(t *testing.T) {
	if testing.Short() {
		t.Skip("50 MB transfer")
	}
	e := newEnv(t)
	c := e.dial(t, wiki).(interface {
		net.Conn
		CloseWrite() error
	})
	const size = 50 << 20
	src := make([]byte, size)
	if _, err := rand.Read(src); err != nil {
		t.Fatal(err)
	}
	want := sha256.Sum256(src)
	errc := make(chan error, 1)
	go func() {
		_, err := c.Write(src)
		if err == nil {
			err = c.CloseWrite()
		}
		errc <- err
	}()
	h := sha256.New()
	_ = c.SetReadDeadline(time.Now().Add(120 * time.Second))
	n, err := io.Copy(h, c)
	if err != nil {
		t.Fatal(err)
	}
	if err := <-errc; err != nil {
		t.Fatal(err)
	}
	if n != size || !bytes.Equal(h.Sum(nil), want[:]) {
		t.Fatalf("got %d bytes, hash mismatch=%v", n, !bytes.Equal(h.Sum(nil), want[:]))
	}
	e.waitStats(t, func(st netstack.Stats) bool { return st.BytesIn == size && st.BytesOut == size })
}

func TestCloseFlows(t *testing.T) {
	e := newEnv(t)
	c := e.dial(t, wiki)
	e.waitFlow(t, netstack.FlowOpen)
	e.ns.CloseFlows()
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read succeeded after CloseFlows")
	}
	e.waitFlow(t, netstack.FlowClose)
}
