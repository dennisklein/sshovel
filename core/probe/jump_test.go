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

// jumpEnv puts a jump host in front of newEnv's server. The jump host knows
// the server as inner.test and forwards nothing else.
type jumpEnv struct {
	*env
	jump *testutil.SSHServer
}

func newJumpEnv(t *testing.T) *jumpEnv {
	e := &jumpEnv{env: newEnv(t)}
	e.jump = testutil.NewSSHServer(t, e.signer.PublicKey())
	e.jump.Dial = func(addr string) (net.Conn, error) {
		if addr == "inner.test:22" {
			return net.Dial("tcp", e.srv.Addr)
		}
		return nil, fmt.Errorf("no route to %s", addr)
	}
	return e
}

func pinJSON(k ssh.PublicKey) string {
	return fmt.Sprintf(`{"type": %q, "fingerprint": %q}`, k.Type(), ssh.FingerprintSHA256(k))
}

func (e *jumpEnv) profile(t *testing.T) *config.Profile {
	p, err := config.Parse([]byte(fmt.Sprintf(`{
  "name": "T", "server": {"host": "inner.test", "port": 22, "user": "tester"},
  "hostKey": %s,
  "jump": {"host": "127.0.0.1", "port": %d, "hostKey": %s},
  "auth": {"kind": "imported", "alias": "k"},
  "routes": ["10.77.0.0/24"], "connectTimeoutSec": 3,
  "dns": {"server": "10.77.0.53", "suffixes": ["corp.test"]}
}`, pinJSON(e.srv.HostSigner.PublicKey()), e.jump.Port, pinJSON(e.jump.HostSigner.PublicKey()))))
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestJumpAllPass(t *testing.T) {
	e := newJumpEnv(t)
	cs := e.run(t, e.profile(t))
	want := "jumpReachable=passed jumpIdentity=passed jumpAuth=passed reachable=passed identity=passed auth=passed forwarding=passed dns=passed "
	if got := statuses(cs); got != want {
		t.Fatal(got)
	}
	if cs[0].Ms <= 0 || cs[3].Ms <= 0 || cs[7].Ms <= 0 {
		t.Errorf("timings %+v", cs)
	}
}

func TestJumpFailuresStopTheRest(t *testing.T) {
	cases := []struct {
		name  string
		setup func(e *jumpEnv, p *config.Profile)
		want  string
	}{
		{"jump unreachable", func(_ *jumpEnv, p *config.Profile) { p.Jump.Port = 1 },
			"jumpReachable=failed(HOST_UNREACHABLE) jumpIdentity=notRun jumpAuth=notRun reachable=notRun identity=notRun auth=notRun forwarding=notRun dns=notRun "},
		{"jump unpinned", func(_ *jumpEnv, p *config.Profile) { p.Jump.HostKey = nil },
			"jumpReachable=passed jumpIdentity=failed(HOST_KEY_UNVERIFIED) jumpAuth=notRun reachable=notRun identity=notRun auth=notRun forwarding=notRun dns=notRun "},
		{"jump refuses forwarding", func(e *jumpEnv, _ *config.Profile) { e.jump.ProhibitForwarding.Store(true) },
			"jumpReachable=passed jumpIdentity=passed jumpAuth=passed reachable=failed(FORWARDING_DENIED) identity=notRun auth=notRun forwarding=notRun dns=notRun "},
		{"server unknown to the jump host", func(_ *jumpEnv, p *config.Profile) { p.Server.Host = "other.test" },
			"jumpReachable=passed jumpIdentity=passed jumpAuth=passed reachable=failed(HOST_UNREACHABLE) identity=notRun auth=notRun forwarding=notRun dns=notRun "},
		{"server unpinned", func(_ *jumpEnv, p *config.Profile) { p.HostKey = nil },
			"jumpReachable=passed jumpIdentity=passed jumpAuth=passed reachable=passed identity=failed(HOST_KEY_UNVERIFIED) auth=notRun forwarding=notRun dns=notRun "},
		{"server refuses the key", func(e *jumpEnv, _ *config.Profile) {
			e.srv.Close()
			stranger := testutil.NewSSHServerWithHostKey(t, e.srv.HostSigner) // same identity, no keys
			e.jump.Dial = func(string) (net.Conn, error) { return net.Dial("tcp", stranger.Addr) }
		},
			"jumpReachable=passed jumpIdentity=passed jumpAuth=passed reachable=passed identity=passed auth=failed(AUTH_FAILED) forwarding=notRun dns=notRun "},
		{"server refuses forwarding", func(e *jumpEnv, _ *config.Profile) { e.srv.ProhibitForwarding.Store(true) },
			"jumpReachable=passed jumpIdentity=passed jumpAuth=passed reachable=passed identity=passed auth=passed forwarding=failed(FORWARDING_DENIED) dns=notRun "},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e := newJumpEnv(t)
			p := e.profile(t)
			c.setup(e, p)
			if got := statuses(e.run(t, p)); got != c.want {
				t.Errorf("got  %s\nwant %s", got, c.want)
			}
		})
	}
}

func TestJumpRefusesKey(t *testing.T) {
	e := newJumpEnv(t)
	other, _ := ssh.NewSignerFromKey(testutil.NewECDSAKey(t))
	p := e.profile(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	got := statuses(Run(ctx, p, sshx.OptionsFor(p, other), nil))
	want := "jumpReachable=passed jumpIdentity=passed jumpAuth=failed(AUTH_FAILED) reachable=notRun identity=notRun auth=notRun forwarding=notRun dns=notRun "
	if got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
}
