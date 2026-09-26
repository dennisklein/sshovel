// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package engine_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sort"
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

// ---- fake clock -----------------------------------------------------------

type fakeClock struct {
	mu     sync.Mutex
	now    time.Time
	timers []*fakeTimer
	added  chan time.Duration
}

type fakeTimer struct {
	c        chan time.Time
	deadline time.Time
	stopped  bool
}

func (t *fakeTimer) C() <-chan time.Time { return t.c }
func (t *fakeTimer) Stop() bool {
	t.stopped = true
	return true
}

func newFakeClock() *fakeClock {
	return &fakeClock{now: time.Date(2026, 9, 25, 12, 0, 0, 0, time.UTC), added: make(chan time.Duration, 100)}
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) NewTimer(d time.Duration) engine.Timer {
	c.mu.Lock()
	t := &fakeTimer{c: make(chan time.Time, 1), deadline: c.now.Add(d)}
	c.timers = append(c.timers, t)
	c.mu.Unlock()
	c.added <- d
	return t
}

// Advance moves time forward and fires due timers.
func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
	keep := c.timers[:0]
	for _, t := range c.timers {
		if !t.stopped && !t.deadline.After(c.now) {
			t.c <- c.now
			continue
		}
		if !t.stopped {
			keep = append(keep, t)
		}
	}
	c.timers = keep
}

// nextTimer waits until the engine arms a backoff timer and returns its delay.
func (c *fakeClock) nextTimer(t *testing.T) time.Duration {
	t.Helper()
	select {
	case d := <-c.added:
		return d
	case <-time.After(10 * time.Second):
		t.Fatal("engine armed no timer")
		return 0
	}
}

// ---- state recorder -------------------------------------------------------

type recorder struct {
	mu   sync.Mutex
	all  []engine.Status
	cond *sync.Cond
}

func newRecorder() *recorder {
	r := &recorder{}
	r.cond = sync.NewCond(&r.mu)
	return r
}

func (r *recorder) on(s engine.Status) {
	r.mu.Lock()
	r.all = append(r.all, s)
	r.mu.Unlock()
	r.cond.Broadcast()
}

// wait blocks until a status after index from satisfies pred; it returns the
// status and the index after it.
func (r *recorder) wait(t *testing.T, from int, what string, pred func(engine.Status) bool) (engine.Status, int) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	go func() {
		time.Sleep(10 * time.Second)
		r.cond.Broadcast()
	}()
	r.mu.Lock()
	defer r.mu.Unlock()
	for {
		for i := from; i < len(r.all); i++ {
			if pred(r.all[i]) {
				return r.all[i], i + 1
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s; states: %s", what, r.stringLocked())
		}
		r.cond.Wait()
	}
}

func (r *recorder) waitState(t *testing.T, from int, st engine.State) (engine.Status, int) {
	t.Helper()
	return r.wait(t, from, string(st), func(s engine.Status) bool { return s.State == st })
}

func (r *recorder) states(from int) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for _, s := range r.all[from:] {
		x := string(s.State)
		if s.Step != "" {
			x += ":" + s.Step
		}
		out = append(out, x)
	}
	return out
}

func (r *recorder) len() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.all)
}

func (r *recorder) stringLocked() string {
	var out []string
	for _, s := range r.all {
		out = append(out, fmt.Sprintf("%+v", s))
	}
	return strings.Join(out, "\n")
}

// ---- fixture --------------------------------------------------------------

type fixture struct {
	srv     *testutil.SSHServer
	dns     *testutil.FakeDNS
	clock   *fakeClock
	rec     *recorder
	profile *config.Profile
	key     *ecdsa.PrivateKey
	params  engine.Params
}

var (
	wiki = netip.MustParseAddrPort("10.77.0.20:80")
)

func newFixture(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{key: testutil.NewECDSAKey(t), clock: newFakeClock(), rec: newRecorder()}
	pub, _ := ssh.NewPublicKey(&f.key.PublicKey)
	f.srv = testutil.NewSSHServer(t, pub)
	f.dns = testutil.NewFakeDNS(t)
	f.dns.A["wiki.corp.test."] = []string{"10.77.0.20"}
	echo := testutil.EchoServer(t)
	f.srv.Dial = func(addr string) (net.Conn, error) {
		switch addr {
		case wiki.String():
			return net.Dial("tcp", echo.Addr().String())
		case "10.77.0.53:53":
			return net.Dial("tcp", f.dns.Addr)
		}
		return nil, errors.New("connection refused")
	}
	pkix, _ := x509.MarshalPKIXPublicKey(&f.key.PublicKey)
	hk := f.srv.HostSigner.PublicKey()
	f.profile = &config.Profile{
		Name:    "Test",
		Server:  config.Server{Host: "127.0.0.1", Port: f.srv.Port, User: "tester"},
		Auth:    config.Auth{Kind: config.AuthKeystore, Alias: "k1", PublicKeyPkix: pkix},
		HostKey: &config.HostKey{Type: hk.Type(), Fingerprint: ssh.FingerprintSHA256(hk)},
		Routes:  []string{"10.77.0.0/24"},
		DNS:     config.DNS{Server: "10.77.0.53", Suffixes: []string{"corp.test"}, HideAAAA: true},
	}
	raw, _ := jsonOf(f.profile)
	p, err := config.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	if issues := p.Validate(); config.HasErrors(issues) {
		t.Fatalf("fixture profile invalid: %+v", issues)
	}
	f.profile = p
	f.params = engine.Params{
		Profile: p,
		Clock:   f.clock,
		Rand:    func() float64 { return 0.5 }, // no jitter
		Callbacks: engine.Callbacks{
			SignDigest: func(alias string, d []byte) ([]byte, error) {
				if alias != "k1" {
					return nil, errors.New("unknown alias")
				}
				return ecdsa.SignASN1(rand.Reader, f.key, d)
			},
			OnState: f.rec.on,
		},
	}
	return f
}

func (f *fixture) start(t *testing.T, peer *testutil.Peer) *engine.Engine {
	t.Helper()
	signer, err := sshx.SignerFor(f.profile, f.params.Callbacks.SignDigest, nil)
	if err != nil {
		t.Fatal(err)
	}
	f.params.Signer = signer
	e := engine.New(f.params)
	var err2 error
	if peer != nil {
		err2 = e.Start(peer.Link)
	} else {
		err2 = e.Start(nil)
	}
	if err2 != nil {
		t.Fatal(err2)
	}
	t.Cleanup(e.Stop)
	return e
}

func newPeer(t *testing.T) *testutil.Peer {
	return testutil.NewPeer(t, netip.MustParseAddr("198.18.0.1"), 1500)
}

func echo(t *testing.T, peer *testutil.Peer, dst netip.AddrPort) error {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := peer.DialTCP(ctx, dst)
	if err != nil {
		return err
	}
	defer c.Close()
	if _, err := c.Write([]byte("hi")); err != nil {
		return err
	}
	b := make([]byte, 2)
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(c, b); err != nil {
		return err
	}
	if string(b) != "hi" {
		return fmt.Errorf("echo got %q", b)
	}
	return nil
}

// ---- tests ----------------------------------------------------------------

func TestConnectWithLinkAndStop(t *testing.T) {
	f := newFixture(t)
	peer := newPeer(t)
	e := f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)
	got := strings.Join(f.rec.states(0), " ")
	want := "connecting:resolving connecting:identity connecting:auth connecting:tunnel on"
	if got != want {
		t.Errorf("states %q\nwant   %q", got, want)
	}
	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
	e.Stop()
	if e.Status().State != engine.Off {
		t.Error("not off when Stop returned")
	}
	f.rec.waitState(t, i, engine.Off)
	if got := strings.Join(f.rec.states(i), " "); got != "disconnecting off" {
		t.Errorf("stop states %q", got)
	}
	if e.Status().State != engine.Off {
		t.Error("not off after Stop")
	}
}

func TestSSHReadyThenAttach(t *testing.T) {
	f := newFixture(t)
	e := f.start(t, nil)
	_, i := f.rec.waitState(t, 0, engine.SSHReady)
	peer := newPeer(t)
	if err := e.AttachLink(peer.Link); err != nil {
		t.Fatal(err)
	}
	f.rec.waitState(t, i, engine.On)
	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
	if err := e.AttachLink(peer.Link); err == nil {
		t.Error("second AttachLink succeeded")
	}
}

func TestNoRetryOnPermanentErrors(t *testing.T) {
	_, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	other, _ := ssh.NewSignerFromKey(otherPriv)
	cases := []struct {
		name   string
		mutate func(*fixture)
		code   errcode.Code
	}{
		{"auth", func(f *fixture) {
			stranger := testutil.NewECDSAKey(t) // not in authorized_keys
			f.profile.Auth.PublicKeyPkix, _ = x509.MarshalPKIXPublicKey(&stranger.PublicKey)
			f.params.Callbacks.SignDigest = func(_ string, d []byte) ([]byte, error) {
				return ecdsa.SignASN1(rand.Reader, stranger, d)
			}
		}, errcode.AuthFailed},
		{"unverified", func(f *fixture) { f.profile.HostKey = nil }, errcode.HostKeyUnverified},
		{"mismatch", func(f *fixture) {
			f.profile.HostKey = &config.HostKey{Type: other.PublicKey().Type(), Fingerprint: ssh.FingerprintSHA256(other.PublicKey())}
		}, errcode.HostKeyMismatch},
		{"key unavailable", func(f *fixture) {
			f.params.Callbacks.SignDigest = func(string, []byte) ([]byte, error) { return nil, errors.New("key invalidated") }
		}, errcode.KeyUnavailable},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			tc.mutate(f)
			e := f.start(t, nil)
			st, _ := f.rec.waitState(t, 0, engine.NeedsAttention)
			if st.Code != string(tc.code) {
				t.Fatalf("code %s, want %s", st.Code, tc.code)
			}
			// Host key errors carry the key the server presented; nothing else does.
			hostKeyErr := tc.code == errcode.HostKeyUnverified || tc.code == errcode.HostKeyMismatch
			if hostKeyErr && (st.HostKey == nil || st.HostKey.Fingerprint != ssh.FingerprintSHA256(f.srv.HostSigner.PublicKey())) {
				t.Errorf("hostKey %+v", st.HostKey)
			}
			if !hostKeyErr && st.HostKey != nil {
				t.Errorf("unexpected hostKey %+v", st.HostKey)
			}
			// Give a buggy engine time to retry, then check it didn't.
			time.Sleep(100 * time.Millisecond)
			f.clock.Advance(time.Hour)
			time.Sleep(100 * time.Millisecond)
			if n := f.srv.Accepts.Load(); n != 1 {
				t.Errorf("%d connection attempts, want 1", n)
			}
			if e.Status().State != engine.NeedsAttention {
				t.Errorf("state %s", e.Status().State)
			}
			if e.Stats().LastError != string(tc.code) {
				t.Errorf("lastError %q", e.Stats().LastError)
			}
			// NetworkChanged doesn't retry either; RetryNow does.
			e.NetworkChanged()
			time.Sleep(50 * time.Millisecond)
			if n := f.srv.Accepts.Load(); n != 1 {
				t.Errorf("NetworkChanged retried a permanent error")
			}
			e.RetryNow()
			deadline := time.Now().Add(5 * time.Second)
			for f.srv.Accepts.Load() < 2 && time.Now().Before(deadline) {
				time.Sleep(10 * time.Millisecond)
			}
			if f.srv.Accepts.Load() < 2 {
				t.Error("RetryNow did not retry")
			}
		})
	}
}

func TestBackoffTiming(t *testing.T) {
	f := newFixture(t)
	f.srv.Close() // nothing listens: every dial fails with HOST_UNREACHABLE
	e := f.start(t, nil)
	want := []time.Duration{1, 2, 4, 8, 16, 30, 30}
	for i, w := range want {
		d := f.clock.nextTimer(t)
		if d != w*time.Second {
			t.Fatalf("attempt %d: backoff %s, want %s", i+1, d, w*time.Second)
		}
		st := e.Status()
		if st.State != engine.Reconnecting || st.Attempt != i+1 || st.Code != string(errcode.HostUnreachable) ||
			st.Reason != engine.ReasonDialFailed || st.NextRetryAt != f.clock.Now().Add(d).UnixMilli() {
			t.Fatalf("attempt %d: status %+v", i+1, st)
		}
		f.clock.Advance(d)
	}
}

func TestBackoffJitter(t *testing.T) {
	for _, r := range []float64{0, 0.999999} {
		f := newFixture(t)
		f.srv.Close()
		f.params.Rand = func() float64 { return r }
		f.start(t, nil)
		d := f.clock.nextTimer(t)
		lo, hi := 800*time.Millisecond, 1200*time.Millisecond
		if d < lo || d > hi {
			t.Errorf("r=%v: first backoff %s outside [%s, %s]", r, d, lo, hi)
		}
	}
}

func TestNetworkChangedShortCircuitsBackoff(t *testing.T) {
	f := newFixture(t)
	f.srv.Close()
	e := f.start(t, nil)
	for range 5 { // climb to a 16 s backoff
		f.clock.Advance(f.clock.nextTimer(t))
	}
	if d := f.clock.nextTimer(t); d != 30*time.Second {
		t.Fatalf("backoff %s", d)
	}
	before := f.rec.len()
	e.NetworkChanged() // no Advance: the retry must happen now
	st, _ := f.rec.wait(t, before, "reconnect after NetworkChanged", func(s engine.Status) bool {
		return s.State == engine.Reconnecting && s.Reason == engine.ReasonNetworkChanged
	})
	if st.NextRetryAt != 0 {
		t.Errorf("waiting again instead of dialing: %+v", st)
	}
	// It dialed immediately and failed; backoff restarts at 1 s.
	if d := f.clock.nextTimer(t); d != time.Second {
		t.Errorf("backoff after network change %s, want 1s", d)
	}
}

func TestNetworkChangedWhileOn(t *testing.T) {
	f := newFixture(t)
	peer := newPeer(t)
	e := f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)
	e.NetworkChanged()
	st, i := f.rec.waitState(t, i, engine.Reconnecting)
	if st.Reason != engine.ReasonNetworkChanged {
		t.Errorf("reason %q", st.Reason)
	}
	f.rec.waitState(t, i, engine.On) // no backoff: straight back up
	// The server counts a handshake only after the client already saw it
	// succeed, so poll.
	for deadline := time.Now().Add(5 * time.Second); f.srv.Handshakes.Load() < 2 && time.Now().Before(deadline); {
		time.Sleep(time.Millisecond)
	}
	if n := f.srv.Handshakes.Load(); n != 2 {
		t.Errorf("%d handshakes, want 2", n)
	}
	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
}

func TestReconnectAfterDrop(t *testing.T) {
	f := newFixture(t)
	peer := newPeer(t)
	e := f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)

	f.srv.KillConnections()
	st, i := f.rec.waitState(t, i, engine.Reconnecting)
	if st.Reason != engine.ReasonClosed {
		t.Errorf("reason %q", st.Reason)
	}
	// While reconnecting, new app connections are reset at once.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	if c, err := peer.DialTCP(ctx, wiki); err == nil {
		c.Close()
		t.Error("connection accepted while reconnecting")
	} else if time.Since(start) > time.Second {
		t.Errorf("RST took %s", time.Since(start))
	}
	// Intranet DNS fails fast with SERVFAIL.
	if rc := lookup(t, peer, "wiki.corp.test."); rc != dns.RcodeServerFailure {
		t.Errorf("DNS while reconnecting: rcode %s", dns.RcodeToString[rc])
	}

	d := f.clock.nextTimer(t)
	if d != time.Second {
		t.Errorf("first backoff %s", d)
	}
	f.clock.Advance(d)
	f.rec.waitState(t, i, engine.On)
	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
	_ = e
}

func TestStableConnectionResetsBackoff(t *testing.T) {
	f := newFixture(t)
	peer := newPeer(t)
	f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)
	// Two quick drops: 1 s, then 2 s.
	for _, want := range []time.Duration{time.Second, 2 * time.Second} {
		f.srv.KillConnections()
		d := f.clock.nextTimer(t)
		if d != want {
			t.Fatalf("backoff %s, want %s", d, want)
		}
		f.clock.Advance(d)
		_, i = f.rec.waitState(t, i, engine.On)
	}
	// A minute of stability resets it.
	f.clock.Advance(61 * time.Second)
	f.srv.KillConnections()
	if d := f.clock.nextTimer(t); d != time.Second {
		t.Errorf("backoff after stable minute %s, want 1s", d)
	}
}

func TestKeepaliveFailure(t *testing.T) {
	f := newFixture(t)
	f.params.Keepalive = 50 * time.Millisecond
	peer := newPeer(t)
	f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)
	time.Sleep(200 * time.Millisecond) // healthy keepalives don't disturb On
	if s := f.rec.states(i); len(s) != 0 {
		t.Fatalf("state changes while healthy: %v", s)
	}
	f.srv.Stall()
	st, _ := f.rec.waitState(t, i, engine.Reconnecting)
	if st.Reason != engine.ReasonKeepalive {
		t.Errorf("reason %q", st.Reason)
	}
	f.srv.Resume()
}

func TestWarningsAndStats(t *testing.T) {
	f := newFixture(t)
	peer := newPeer(t)
	e := f.start(t, peer)
	_, i := f.rec.waitState(t, 0, engine.On)

	if err := echo(t, peer, wiki); err != nil {
		t.Fatal(err)
	}
	if rc := lookup(t, peer, "wiki.corp.test."); rc != dns.RcodeSuccess {
		t.Fatalf("intranet lookup rcode %s", dns.RcodeToString[rc])
	}
	f.clock.Advance(42 * time.Second)
	// The relay counts bytes after the app may already have read them, and
	// the flow closes asynchronously: poll until the counters settle.
	var st engine.Stats
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		st = e.Stats()
		if st.BytesIn == 2 && st.BytesOut == 2 && st.ActiveFlows == 0 {
			break
		}
	}
	if st.UptimeSec != 42 || st.BytesIn != 2 || st.BytesOut != 2 || st.ActiveFlows != 0 || st.DNSTunneled != 1 {
		t.Errorf("stats %+v", st)
	}

	f.srv.ProhibitForwarding.Store(true)
	_ = echo(t, peer, wiki)
	s, i := f.rec.wait(t, i, "FORWARDING_DENIED warning", func(s engine.Status) bool {
		return s.State == engine.On && len(s.Warnings) > 0
	})
	if s.Warnings[0] != string(errcode.ForwardingDenied) {
		t.Errorf("warnings %v", s.Warnings)
	}
	// The DNS channel is already open, so kill it to make the resolver
	// unreachable too.
	f.dns.KillConnections()
	lookup(t, peer, "wiki.corp.test.")
	s, _ = f.rec.wait(t, i, "DNS_UNREACHABLE warning", func(s engine.Status) bool {
		return len(s.Warnings) == 2
	})
	sort.Strings(s.Warnings)
	if s.Warnings[0] != string(errcode.DNSUnreachable) {
		t.Errorf("warnings %v", s.Warnings)
	}
}

func TestStopDuringBackoffAndBeforeStart(t *testing.T) {
	f := newFixture(t)
	f.srv.Close()
	e := f.start(t, nil)
	f.clock.nextTimer(t)
	done := make(chan struct{})
	go func() { e.Stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Stop hung during backoff")
	}
	if e.Status().State != engine.Off {
		t.Error("not off")
	}

	g := newFixture(t)
	signer, _ := sshx.SignerFor(g.profile, g.params.Callbacks.SignDigest, nil)
	g.params.Signer = signer
	e2 := engine.New(g.params)
	e2.Stop()
	if err := e2.Start(nil); err == nil {
		t.Error("Start after Stop succeeded")
	}
}

func TestStopFromOnState(t *testing.T) {
	// Kotlin may stop the engine from its OnState handler, e.g. on
	// needsAttention. That must not deadlock.
	f := newFixture(t)
	f.profile.HostKey = nil
	var e *engine.Engine
	stopped := make(chan struct{})
	f.params.Callbacks.OnState = func(s engine.Status) {
		f.rec.on(s)
		if s.State == engine.NeedsAttention {
			e.Stop()
			close(stopped)
		}
	}
	signer, _ := sshx.SignerFor(f.profile, f.params.Callbacks.SignDigest, nil)
	f.params.Signer = signer
	e = engine.New(f.params)
	if err := e.Start(nil); err != nil {
		t.Fatal(err)
	}
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("Stop from OnState deadlocked")
	}
	f.rec.waitState(t, 0, engine.Off)
	e.Stop() // idempotent
}

func TestStateOrder(t *testing.T) {
	// Statuses must reach OnState in the order the engine entered them, and
	// an On after a reconnect must not carry warnings from the outage, even
	// when DNS failures race with the drop.
	f := newFixture(t)
	peer := newPeer(t)
	e := f.start(t, peer)
	f.rec.waitState(t, 0, engine.On)
	for k := int64(2); k <= 21; k++ {
		f.dns.KillConnections()
		go lookupQuiet(peer, "wiki.corp.test.")
		f.srv.KillConnections()
		f.clock.Advance(f.clock.nextTimer(t))
		// Wait for the new connection itself; an On status alone may just be
		// a warning update on the old one.
		deadline := time.Now().Add(5 * time.Second)
		for f.srv.Handshakes.Load() < k || e.Status().State != engine.On {
			if time.Now().After(deadline) {
				t.Fatalf("reconnect %d: handshakes=%d state=%s", k, f.srv.Handshakes.Load(), e.Status().State)
			}
			time.Sleep(time.Millisecond)
		}
	}
	f.rec.mu.Lock()
	all := append([]engine.Status(nil), f.rec.all...)
	f.rec.mu.Unlock()
	for j, s := range all {
		if j > 0 && s.State == engine.On && all[j-1].State == engine.Reconnecting && len(s.Warnings) > 0 {
			t.Fatalf("On after a reconnect carried stale warnings: %+v", s)
		}
	}
	// Once quiet, the last delivered status is the engine's status.
	time.Sleep(100 * time.Millisecond)
	f.rec.mu.Lock()
	last := f.rec.all[len(f.rec.all)-1]
	f.rec.mu.Unlock()
	if st := e.Status(); st.State != last.State || len(st.Warnings) != len(last.Warnings) {
		t.Errorf("engine %+v, last delivered %+v", st, last)
	}
}

func lookupQuiet(peer *testutil.Peer, name string) {
	u, err := peer.DialUDP(netip.MustParseAddrPort("198.18.0.53:53"))
	if err != nil {
		return
	}
	defer u.Close()
	m := new(dns.Msg)
	m.SetQuestion(name, dns.TypeA)
	b, _ := m.Pack()
	_, _ = u.Write(b)
	_ = u.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, _ = u.Read(make([]byte, 512))
}

// lookup sends an A query from the peer to the DNS virtual IP over UDP.
func lookup(t *testing.T, peer *testutil.Peer, name string) int {
	t.Helper()
	u, err := peer.DialUDP(netip.MustParseAddrPort("198.18.0.53:53"))
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	m := new(dns.Msg)
	m.SetQuestion(name, dns.TypeA)
	b, _ := m.Pack()
	if _, err := u.Write(b); err != nil {
		t.Fatal(err)
	}
	_ = u.SetReadDeadline(time.Now().Add(8 * time.Second))
	buf := make([]byte, 4096)
	n, err := u.Read(buf)
	if err != nil {
		t.Fatalf("DNS read: %v", err)
	}
	r := new(dns.Msg)
	if err := r.Unpack(buf[:n]); err != nil {
		t.Fatal(err)
	}
	return r.Rcode
}
