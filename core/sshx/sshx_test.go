// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/sshx"
)

// keystore simulates Android Keystore: it signs a digest with NONEwithECDSA
// semantics and returns DER, exactly what Kotlin hands to Go.
type keystore struct {
	key   *ecdsa.PrivateKey
	calls atomic.Int64
	fail  error
}

func (k *keystore) sign(alias string, digest []byte) ([]byte, error) {
	k.calls.Add(1)
	if alias != "sshovel-key-1" {
		return nil, errors.New("no such alias")
	}
	if k.fail != nil {
		return nil, k.fail
	}
	return ecdsa.SignASN1(rand.Reader, k.key, digest)
}

func newKeystore(t *testing.T) (*keystore, []byte, ssh.PublicKey) {
	k := &keystore{key: testutil.NewECDSAKey(t)}
	pkix, err := x509.MarshalPKIXPublicKey(&k.key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	pub, err := ssh.NewPublicKey(&k.key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return k, pkix, pub
}

func pinOf(k ssh.PublicKey) *config.HostKey {
	return &config.HostKey{Type: k.Type(), Fingerprint: ssh.FingerprintSHA256(k)}
}

func opts(srv *testutil.SSHServer, signer ssh.Signer, pin *config.HostKey) sshx.Options {
	return sshx.Options{Host: "127.0.0.1", Port: srv.Port, User: "tester", Signer: signer, Pin: pin, Timeout: 5 * time.Second}
}

func TestPlatformSignerRoundTrip(t *testing.T) {
	ks, pkix, pub := newKeystore(t)
	srv := testutil.NewSSHServer(t, pub)
	signer, err := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	if err != nil {
		t.Fatal(err)
	}
	if signer.PublicKey().Type() != ssh.KeyAlgoECDSA256 {
		t.Fatalf("key type %s", signer.PublicKey().Type())
	}
	var steps []sshx.Step
	o := opts(srv, signer, pinOf(srv.HostSigner.PublicKey()))
	o.OnStep = func(s sshx.Step) { steps = append(steps, s) }
	c, err := sshx.Dial(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if ks.calls.Load() == 0 {
		t.Error("Keystore signer was never called")
	}
	if got := strings.Join([]string{string(steps[0]), string(steps[1]), string(steps[2])}, ","); got != "resolving,identity,auth" {
		t.Errorf("steps = %v", steps)
	}
	// And the forwarding works.
	echo := testutil.EchoServer(t)
	conn, err := c.DialTCP(context.Background(), netip.MustParseAddrPort(echo.Addr().String()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("echo got %q, %v", buf, err)
	}
}

func TestDialErrors(t *testing.T) {
	ks, pkix, pub := newKeystore(t)
	signer, _ := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	srv := testutil.NewSSHServer(t, pub)
	good := pinOf(srv.HostSigner.PublicKey())

	_, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	otherSigner, _ := ssh.NewSignerFromKey(otherPriv)
	wrong := pinOf(otherSigner.PublicKey())

	strangerKS, strangerPKIX, _ := newKeystore(t)
	stranger, _ := sshx.NewPlatformSigner(strangerPKIX, "sshovel-key-1", strangerKS.sign)

	brokenKS := &keystore{key: ks.key, fail: errors.New("key permanently invalidated")}
	broken, _ := sshx.NewPlatformSigner(pkix, "sshovel-key-1", brokenKS.sign)

	closed, _ := net.Listen("tcp", "127.0.0.1:0")
	closedPort := closed.Addr().(*net.TCPAddr).Port
	closed.Close()

	cases := []struct {
		name string
		o    sshx.Options
		want errcode.Code
	}{
		{"unverified", opts(srv, signer, nil), errcode.HostKeyUnverified},
		{"mismatch", opts(srv, signer, wrong), errcode.HostKeyMismatch},
		{"mismatch same type different key", opts(srv, signer, &config.HostKey{Type: good.Type, Fingerprint: wrong.Fingerprint}), errcode.HostKeyMismatch},
		{"auth failed", opts(srv, stranger, good), errcode.AuthFailed},
		{"key unavailable", opts(srv, broken, good), errcode.KeyUnavailable},
		{"unreachable", sshx.Options{Host: "127.0.0.1", Port: closedPort, User: "tester", Signer: signer, Pin: good, Timeout: 2 * time.Second}, errcode.HostUnreachable},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			before := srv.Handshakes.Load()
			c, err := sshx.Dial(context.Background(), tc.o)
			if err == nil {
				c.Close()
				t.Fatal("Dial succeeded")
			}
			if got := errcode.Of(err); got != tc.want {
				t.Fatalf("code %s, want %s (%v)", got, tc.want, err)
			}
			if srv.Handshakes.Load() != before {
				t.Error("server saw a successful authentication")
			}
		})
	}
	t.Run("host key failure happens before auth", func(t *testing.T) {
		before := ks.calls.Load()
		_, err := sshx.Dial(context.Background(), opts(srv, signer, wrong))
		if errcode.Of(err) != errcode.HostKeyMismatch {
			t.Fatal(err)
		}
		if ks.calls.Load() != before {
			t.Error("signed with the key for a server whose identity didn't match")
		}
	})
}

func TestDialTimeout(t *testing.T) {
	// A listener that never speaks SSH: the handshake must time out.
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
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
	start := time.Now()
	_, err := sshx.Dial(context.Background(), sshx.Options{Host: "127.0.0.1", Port: ln.Addr().(*net.TCPAddr).Port, User: "u", Timeout: 300 * time.Millisecond})
	if errcode.Of(err) != errcode.HostUnreachable {
		t.Fatalf("got %v", err)
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Errorf("timeout took %s", d)
	}
}

func TestProtectAndLookup(t *testing.T) {
	ks, pkix, pub := newKeystore(t)
	signer, _ := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	srv := testutil.NewSSHServer(t, pub)
	o := opts(srv, signer, pinOf(srv.HostSigner.PublicKey()))
	o.Host = "jump.example.test"
	var protected, looked atomic.Int64
	o.Protect = func(fd int) bool { protected.Add(1); return fd > 0 }
	o.Lookup = func(_ context.Context, host string) ([]netip.Addr, error) {
		looked.Add(1)
		if host != "jump.example.test" {
			t.Errorf("lookup of %q", host)
		}
		return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
	}
	c, err := sshx.Dial(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if protected.Load() != 1 || looked.Load() != 1 {
		t.Errorf("protect=%d lookup=%d", protected.Load(), looked.Load())
	}

	o.Protect = func(int) bool { return false }
	if _, err := sshx.Dial(context.Background(), o); errcode.Of(err) != errcode.HostUnreachable {
		t.Errorf("failed protect: got %v", err)
	}
}

func TestFetchHostKey(t *testing.T) {
	_, _, pub := newKeystore(t)
	srv := testutil.NewSSHServer(t, pub)
	info, err := sshx.FetchHostKey(context.Background(), sshx.Options{Host: "127.0.0.1", Port: srv.Port, User: "tester", Timeout: 5 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	hk := srv.HostSigner.PublicKey()
	if info.Type != hk.Type() || info.Fingerprint != ssh.FingerprintSHA256(hk) {
		t.Errorf("got %+v", info)
	}
	if !strings.HasPrefix(info.Line, "ssh-ed25519 AAAA") {
		t.Errorf("line %q", info.Line)
	}
	// Aborted before auth: the server never saw an authenticated session.
	time.Sleep(50 * time.Millisecond)
	if srv.Handshakes.Load() != 0 {
		t.Error("FetchHostKey authenticated")
	}
}

func TestDialTCPErrors(t *testing.T) {
	ks, pkix, pub := newKeystore(t)
	signer, _ := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	srv := testutil.NewSSHServer(t, pub)
	c, err := sshx.Dial(context.Background(), opts(srv, signer, pinOf(srv.HostSigner.PublicKey())))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	closed, _ := net.Listen("tcp", "127.0.0.1:0")
	dst := netip.MustParseAddrPort(closed.Addr().String())
	closed.Close()
	if _, err := c.DialTCP(context.Background(), dst); errcode.Of(err) != errcode.DestUnreachable {
		t.Errorf("closed port: %v", err)
	}

	srv.ProhibitForwarding.Store(true)
	if _, err := c.DialTCP(context.Background(), dst); errcode.Of(err) != errcode.ForwardingDenied {
		t.Errorf("prohibited: %v", err)
	}
	srv.ProhibitForwarding.Store(false)

	srv.Dial = func(string) (net.Conn, error) { time.Sleep(time.Second); return nil, errors.New("slow") }
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if _, err := c.DialTCP(ctx, dst); errcode.Of(err) != errcode.DestTimeout {
		t.Errorf("timeout: %v", err)
	}
}

func TestKeepalive(t *testing.T) {
	ks, pkix, pub := newKeystore(t)
	signer, _ := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	srv := testutil.NewSSHServer(t, pub)
	c, err := sshx.Dial(context.Background(), opts(srv, signer, pinOf(srv.HostSigner.PublicKey())))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	// Healthy: keepalives are answered (with REQUEST_FAILURE, like OpenSSH).
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if err := c.Keepalive(ctx, 30*time.Millisecond); err != nil {
		t.Fatalf("healthy keepalive: %v", err)
	}

	// Stalled server: detected within ~3 intervals.
	srv.Stall()
	start := time.Now()
	err = c.Keepalive(context.Background(), 50*time.Millisecond)
	if !errors.Is(err, sshx.ErrKeepaliveTimeout) {
		t.Fatalf("stalled keepalive: %v", err)
	}
	if d := time.Since(start); d < 100*time.Millisecond || d > time.Second {
		t.Errorf("detection took %s", d)
	}
	srv.Resume()
}

func TestParseImportedKeyZeroes(t *testing.T) {
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	block, err := ssh.MarshalPrivateKey(priv, "")
	if err != nil {
		t.Fatal(err)
	}
	b := pem.EncodeToMemory(block)
	s, err := sshx.ParseImportedKey(b)
	if err != nil {
		t.Fatal(err)
	}
	if s.PublicKey().Type() != ssh.KeyAlgoED25519 {
		t.Errorf("type %s", s.PublicKey().Type())
	}
	for i, c := range b {
		if c != 0 {
			t.Fatalf("key byte %d not zeroed", i)
		}
	}
	bad := []byte("garbage")
	if _, err := sshx.ParseImportedKey(bad); errcode.Of(err) != errcode.KeyUnavailable {
		t.Errorf("garbage: %v", err)
	}
	if string(bad) != "\x00\x00\x00\x00\x00\x00\x00" {
		t.Error("bad key not zeroed")
	}
}

func TestAuthorizedKeyLine(t *testing.T) {
	_, pkix, pub := newKeystore(t)
	line, err := sshx.AuthorizedKeyLine(pkix, "sshovel@pixel")
	if err != nil {
		t.Fatal(err)
	}
	want := "restrict,port-forwarding " + strings.TrimSpace(string(ssh.MarshalAuthorizedKey(pub))) + " sshovel@pixel"
	if line != want {
		t.Errorf("got  %q\nwant %q", line, want)
	}
	// The line must parse as an authorized_keys entry with those options.
	k, _, opts, _, err := ssh.ParseAuthorizedKey([]byte(line))
	if err != nil || string(k.Marshal()) != string(pub.Marshal()) || strings.Join(opts, ",") != "restrict,port-forwarding" {
		t.Errorf("parse back: %v %v", opts, err)
	}
}
