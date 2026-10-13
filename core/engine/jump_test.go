// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package engine_test

import (
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/miekg/dns"
	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/engine"
	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/sshx"
)

// withJump puts a jump host in front of the fixture's server, which only the
// jump host can reach, by a name only it resolves (like lxlogin → lxpool).
func withJump(t *testing.T, f *fixture) *testutil.SSHServer {
	t.Helper()
	pub, _ := ssh.NewPublicKey(&f.key.PublicKey)
	jump := testutil.NewSSHServer(t, pub)
	jump.Dial = func(addr string) (net.Conn, error) {
		if addr == "inner.test:22" {
			return net.Dial("tcp", f.srv.Addr)
		}
		return nil, errors.New("connect failed: No route to host")
	}
	jk := jump.HostSigner.PublicKey()
	f.profile.Server.Host, f.profile.Server.Port = "inner.test", 22
	f.profile.Jump = &config.Jump{Host: "127.0.0.1", Port: jump.Port,
		HostKey: &config.HostKey{Type: jk.Type(), Fingerprint: ssh.FingerprintSHA256(jk)}}
	return jump
}

func TestTunnelThroughJumpHost(t *testing.T) {
	f := newFixture(t)
	jump := withJump(t, f)
	var mu sync.Mutex
	var logs []string
	f.params.Callbacks.Log = func(_ int, _, msg string) { mu.Lock(); logs = append(logs, msg); mu.Unlock() }
	peer := newPeer(t)
	f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)
	if got := strings.Join(f.rec.states(0)[:5], ","); got != "connecting:resolving,connecting:identity,connecting:jump,connecting:auth,connecting:tunnel" {
		t.Errorf("states %s", got)
	}
	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
	if rc := lookup(t, peer, "wiki.corp.test."); rc != dns.RcodeSuccess {
		t.Fatalf("intranet lookup rcode %s", dns.RcodeToString[rc])
	}
	mu.Lock()
	if !strings.Contains(strings.Join(logs, "\n"), "via tester@127.0.0.1:") {
		t.Errorf("logs don't name the jump host: %q", logs)
	}
	mu.Unlock()

	// Losing the jump host is losing the connection: reconnect through it.
	jump.KillConnections()
	_, i = f.rec.waitState(t, i, engine.Reconnecting)
	f.clock.Advance(f.clock.nextTimer(t))
	f.rec.waitState(t, i, engine.On)
	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
	if n := jump.Handshakes.Load(); n != 2 {
		t.Errorf("jump host logins %d, want 2", n)
	}
}

func TestJumpHostPermanentErrors(t *testing.T) {
	_, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	other, _ := ssh.NewSignerFromKey(otherPriv)
	wrong := &config.HostKey{Type: other.PublicKey().Type(), Fingerprint: ssh.FingerprintSHA256(other.PublicKey())}
	cases := []struct {
		name   string
		mutate func(*fixture, *testutil.SSHServer)
		code   errcode.Code
		hop    string // HostKey.Hop, if any
		errHop string // Status.Hop
	}{
		{"jump unverified", func(f *fixture, _ *testutil.SSHServer) { f.profile.Jump.HostKey = nil }, errcode.HostKeyUnverified, sshx.HopJump, sshx.HopJump},
		{"jump mismatch", func(f *fixture, _ *testutil.SSHServer) { f.profile.Jump.HostKey = wrong }, errcode.HostKeyMismatch, sshx.HopJump, sshx.HopJump},
		{"server mismatch", func(f *fixture, _ *testutil.SSHServer) { f.profile.HostKey = wrong }, errcode.HostKeyMismatch, sshx.HopServer, sshx.HopServer},
		{"jump refuses to forward", func(_ *fixture, j *testutil.SSHServer) { j.ProhibitForwarding.Store(true) }, errcode.ForwardingDenied, "", sshx.HopServer},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			jump := withJump(t, f)
			tc.mutate(f, jump)
			e := f.start(t, nil)
			st, _ := f.rec.waitState(t, 0, engine.NeedsAttention)
			if st.Code != string(tc.code) {
				t.Fatalf("code %s, want %s (%s)", st.Code, tc.code, st.Detail)
			}
			if st.Hop != tc.errHop {
				t.Errorf("hop %q, want %q", st.Hop, tc.errHop)
			}
			if (st.HostKey != nil) != (tc.hop != "") || (st.HostKey != nil && st.HostKey.Hop != tc.hop) {
				t.Errorf("hostKey %+v, want hop %q", st.HostKey, tc.hop)
			}
			time.Sleep(100 * time.Millisecond)
			f.clock.Advance(time.Hour)
			time.Sleep(100 * time.Millisecond)
			if n := jump.Accepts.Load(); n != 1 {
				t.Errorf("%d attempts, want 1", n)
			}
			if e.Status().State != engine.NeedsAttention {
				t.Errorf("state %s", e.Status().State)
			}
		})
	}
}

func TestJumpHostDownRetries(t *testing.T) {
	f := newFixture(t)
	jump := withJump(t, f)
	jump.Close()
	f.start(t, nil)
	st, _ := f.rec.wait(t, 0, "backoff", func(s engine.Status) bool {
		return s.State == engine.Reconnecting && s.NextRetryAt != 0
	})
	if st.Code != string(errcode.HostUnreachable) || st.Hop != sshx.HopJump {
		t.Errorf("reconnecting %+v, want HOST_UNREACHABLE about the jump host", st)
	}
}
