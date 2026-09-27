// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package probe

import (
	"context"
	"fmt"
	"net"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/sshx"
)

type env struct {
	srv    *testutil.SSHServer
	dns    *testutil.FakeDNS
	signer ssh.Signer
}

func newEnv(t *testing.T) *env {
	signer, err := ssh.NewSignerFromKey(testutil.NewECDSAKey(t))
	if err != nil {
		t.Fatal(err)
	}
	e := &env{srv: testutil.NewSSHServer(t, signer.PublicKey()), dns: testutil.NewFakeDNS(t), signer: signer}
	// The intranet resolver 10.77.0.53 is the fake DNS server on loopback.
	e.srv.Dial = func(addr string) (net.Conn, error) {
		if addr == "10.77.0.53:53" {
			return net.Dial("tcp", e.dns.Addr)
		}
		return nil, fmt.Errorf("no route to %s", addr)
	}
	return e
}

func (e *env) profile(t *testing.T, pinned bool, dnsServer string) *config.Profile {
	hk := ""
	if pinned {
		k := e.srv.HostSigner.PublicKey()
		hk = fmt.Sprintf(`"hostKey": {"type": %q, "fingerprint": %q},`, k.Type(), ssh.FingerprintSHA256(k))
	}
	p, err := config.Parse([]byte(fmt.Sprintf(`{
  "name": "T", "server": {"host": "127.0.0.1", "port": %d, "user": "tester"},
  "auth": {"kind": "imported", "alias": "k"}, %s
  "routes": ["10.77.0.0/24"], "connectTimeoutSec": 3,
  "dns": {"server": %q, "suffixes": ["corp.test"]}
}`, e.srv.Port, hk, dnsServer)))
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func (e *env) run(t *testing.T, p *config.Profile) []Check {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return Run(ctx, p, sshx.OptionsFor(p, e.signer), nil)
}

func statuses(cs []Check) string {
	s := ""
	for _, c := range cs {
		s += c.ID + "=" + c.Status
		if c.Code != "" {
			s += "(" + c.Code + ")"
		}
		s += " "
	}
	return s
}

func TestAllPass(t *testing.T) {
	e := newEnv(t)
	cs := e.run(t, e.profile(t, true, "10.77.0.53"))
	if got := statuses(cs); got != "reachable=passed identity=passed auth=passed forwarding=passed dns=passed " {
		t.Fatal(got)
	}
	if cs[0].Ms <= 0 || cs[4].Ms <= 0 || cs[4].Target != "10.77.0.53:53" {
		t.Errorf("checks %+v", cs)
	}
	if e.dns.Queries.Load() != 1 {
		t.Errorf("dns queries %d", e.dns.Queries.Load())
	}
}

func TestNoDNSServerSkips(t *testing.T) {
	e := newEnv(t)
	got := statuses(e.run(t, e.profile(t, true, "")))
	if got != "reachable=passed identity=passed auth=passed forwarding=skipped dns=skipped " {
		t.Fatal(got)
	}
}

func TestFailuresStopTheRest(t *testing.T) {
	e := newEnv(t)
	cases := []struct {
		name  string
		setup func(p *config.Profile)
		want  string
	}{
		{"unreachable", func(p *config.Profile) { p.Server.Port = 1 },
			"reachable=failed(HOST_UNREACHABLE) identity=notRun auth=notRun forwarding=notRun dns=notRun "},
		{"unpinned", func(p *config.Profile) { p.HostKey = nil },
			"reachable=passed identity=failed(HOST_KEY_UNVERIFIED) auth=notRun forwarding=notRun dns=notRun "},
		{"mismatch", func(p *config.Profile) { p.HostKey.Fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" },
			"reachable=passed identity=failed(HOST_KEY_MISMATCH) auth=notRun forwarding=notRun dns=notRun "},
		{"dns unreachable", func(p *config.Profile) { p.DNS.Server = "10.77.0.99" },
			"reachable=passed identity=passed auth=passed forwarding=passed dns=failed(DNS_UNREACHABLE) "},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			p := e.profile(t, true, "10.77.0.53")
			c.setup(p)
			if got := statuses(e.run(t, p)); got != c.want {
				t.Errorf("got  %s\nwant %s", got, c.want)
			}
		})
	}
}

func TestAuthFailedAndForwardingDenied(t *testing.T) {
	e := newEnv(t)
	other, _ := ssh.NewSignerFromKey(testutil.NewECDSAKey(t))
	p := e.profile(t, true, "10.77.0.53")
	ctx := context.Background()
	got := statuses(Run(ctx, p, sshx.OptionsFor(p, other), nil))
	if got != "reachable=passed identity=passed auth=failed(AUTH_FAILED) forwarding=notRun dns=notRun " {
		t.Errorf("wrong key: %s", got)
	}

	e.srv.ProhibitForwarding.Store(true)
	got = statuses(e.run(t, p))
	if got != "reachable=passed identity=passed auth=passed forwarding=failed(FORWARDING_DENIED) dns=notRun " {
		t.Errorf("prohibited: %s", got)
	}
}

func TestDNSSilent(t *testing.T) {
	e := newEnv(t)
	// A resolver that accepts the connection and never answers.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			defer c.Close()
		}
	}()
	e.srv.Dial = func(string) (net.Conn, error) { return net.Dial("tcp", ln.Addr().String()) }
	start := time.Now()
	got := statuses(e.run(t, e.profile(t, true, "10.77.0.53")))
	if got != "reachable=passed identity=passed auth=passed forwarding=passed dns=failed(DNS_UNREACHABLE) " {
		t.Fatal(got)
	}
	if time.Since(start) > dnsTimeout+5*time.Second {
		t.Errorf("took %v", time.Since(start))
	}
}
