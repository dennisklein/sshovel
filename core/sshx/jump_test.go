// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx_test

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/sshx"
)

// jumpPair is a jump host that only forwards to the server's SSH port, which
// it knows by a name nothing else can resolve: like a login host in front of
// an intranet.
type jumpPair struct {
	jump, server *testutil.SSHServer
	signer       ssh.Signer
}

const innerName = "inner.intranet.test"

func newJumpPair(t *testing.T) *jumpPair {
	t.Helper()
	ks, pkix, pub := newKeystore(t)
	signer, err := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	if err != nil {
		t.Fatal(err)
	}
	p := &jumpPair{jump: testutil.NewSSHServer(t, pub), server: testutil.NewSSHServer(t, pub), signer: signer}
	p.jump.Dial = func(addr string) (net.Conn, error) {
		if addr == net.JoinHostPort(innerName, "22") {
			return net.Dial("tcp", p.server.Addr)
		}
		return nil, errors.New("connect failed: No route to host")
	}
	return p
}

// opts pins both hosts; tests unpin or mispin what they need.
func (p *jumpPair) opts() sshx.Options {
	return sshx.Options{
		Host: innerName, Port: 22, User: "tester", Signer: p.signer,
		Pin: pinOf(p.server.HostSigner.PublicKey()), Timeout: 5 * time.Second,
		Jump: &sshx.Hop{Host: "127.0.0.1", Port: p.jump.Port, User: "tester", Pin: pinOf(p.jump.HostSigner.PublicKey())},
	}
}

func TestDialThroughJumpHost(t *testing.T) {
	p := newJumpPair(t)
	var steps []string
	o := p.opts()
	o.OnStep = func(s sshx.Step) { steps = append(steps, string(s)) }
	c, err := sshx.Dial(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := strings.Join(steps, ","); got != "resolving,identity,jump,auth" {
		t.Errorf("steps = %s", got)
	}
	if p.jump.Handshakes.Load() != 1 || p.server.Handshakes.Load() != 1 {
		t.Errorf("handshakes jump %d server %d", p.jump.Handshakes.Load(), p.server.Handshakes.Load())
	}

	// Forwarded connections leave from the server, not the jump host.
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
	if p.server.Channels.Load() != 1 || p.jump.Channels.Load() != 1 {
		t.Errorf("channels jump %d (want 1, the server's SSH) server %d", p.jump.Channels.Load(), p.server.Channels.Load())
	}
}

func TestJumpHostDeathEndsConnection(t *testing.T) {
	p := newJumpPair(t)
	c, err := sshx.Dial(context.Background(), p.opts())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	done := make(chan error, 1)
	go func() { done <- c.Wait() }()
	p.jump.KillConnections()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Wait didn't return after the jump host connection died")
	}
}

func TestCloseClosesJumpConnection(t *testing.T) {
	p := newJumpPair(t)
	c, err := sshx.Dial(context.Background(), p.opts())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	// The jump server sees its client go away.
	deadline := time.Now().Add(5 * time.Second)
	for p.jump.Open() > 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if n := p.jump.Open(); n != 0 {
		t.Errorf("jump host still has %d connections", n)
	}
}

func TestJumpDialErrors(t *testing.T) {
	_, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	otherSigner, _ := ssh.NewSignerFromKey(otherPriv)
	wrong := pinOf(otherSigner.PublicKey())

	cases := []struct {
		name   string
		mutate func(*testing.T, *jumpPair, *sshx.Options)
		code   errcode.Code
		hop    string // HostKeyError's hop, if any
		detail string
		errHop string // HopOf: which host the error is about
	}{
		{"jump unpinned", func(_ *testing.T, _ *jumpPair, o *sshx.Options) { o.Jump.Pin = nil }, errcode.HostKeyUnverified, sshx.HopJump, "jump host 127.0.0.1", sshx.HopJump},
		{"jump key changed", func(_ *testing.T, _ *jumpPair, o *sshx.Options) { o.Jump.Pin = wrong }, errcode.HostKeyMismatch, sshx.HopJump, "jump host 127.0.0.1", sshx.HopJump},
		{"server unpinned", func(_ *testing.T, _ *jumpPair, o *sshx.Options) { o.Pin = nil }, errcode.HostKeyUnverified, sshx.HopServer, "", sshx.HopServer},
		{"server key changed", func(_ *testing.T, _ *jumpPair, o *sshx.Options) { o.Pin = wrong }, errcode.HostKeyMismatch, sshx.HopServer, "", sshx.HopServer},
		{"jump refuses the key", func(t *testing.T, _ *jumpPair, o *sshx.Options) {
			stranger := testutil.NewSSHServer(t) // authorizes no key
			o.Jump.Port, o.Jump.Pin = stranger.Port, pinOf(stranger.HostSigner.PublicKey())
		}, errcode.AuthFailed, "", "jump host 127.0.0.1", sshx.HopJump},
		{"jump refuses to forward", func(_ *testing.T, p *jumpPair, _ *sshx.Options) { p.jump.ProhibitForwarding.Store(true) }, errcode.ForwardingDenied, "", "jump host refused to forward to " + innerName, sshx.HopServer},
		{"jump can't reach the server", func(_ *testing.T, _ *jumpPair, o *sshx.Options) { o.Host = "elsewhere.test" }, errcode.HostUnreachable, "", "No route to host", sshx.HopServer},
		{"jump host down", func(_ *testing.T, p *jumpPair, _ *sshx.Options) { p.jump.Close() }, errcode.HostUnreachable, "", "jump host 127.0.0.1", sshx.HopJump},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := newJumpPair(t)
			o := p.opts()
			tc.mutate(t, p, &o)
			_, err := sshx.Dial(context.Background(), o)
			if code := errcode.Of(err); code != tc.code {
				t.Fatalf("code %s, want %s (%v)", code, tc.code, err)
			}
			var hke *sshx.HostKeyError
			if errors.As(err, &hke) != (tc.hop != "") || (tc.hop != "" && hke.Received.Hop != tc.hop) {
				t.Errorf("host key error %+v, want hop %q", hke, tc.hop)
			}
			if h := sshx.HopOf(err); h != tc.errHop {
				t.Errorf("HopOf = %q, want %q", h, tc.errHop)
			}
			if !strings.Contains(err.Error(), tc.detail) {
				t.Errorf("error %q doesn't mention %q", err, tc.detail)
			}
		})
	}
}

func TestFetchHostKeysThroughJumpHost(t *testing.T) {
	p := newJumpPair(t)
	o := p.opts()
	o.Pin, o.Jump.Pin = nil, nil

	// First the jump host's key, without logging in anywhere.
	info, err := sshx.FetchHostKey(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	if info.Hop != sshx.HopJump || info.Fingerprint != ssh.FingerprintSHA256(p.jump.HostSigner.PublicKey()) {
		t.Errorf("first hop: %+v", info)
	}
	if p.jump.Handshakes.Load() != 0 {
		t.Error("fetching the jump host's key logged in")
	}

	// The server's key needs the jump host pinned.
	if _, err := sshx.FetchServerHostKey(context.Background(), o); errcode.Of(err) != errcode.HostKeyUnverified {
		t.Errorf("server key with the jump host unpinned: %v", err)
	}
	o.Jump.Pin = pinOf(p.jump.HostSigner.PublicKey())
	info, err = sshx.FetchServerHostKey(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	if info.Hop != sshx.HopServer || info.Fingerprint != ssh.FingerprintSHA256(p.server.HostSigner.PublicKey()) {
		t.Errorf("server: %+v", info)
	}
	if p.server.Handshakes.Load() != 0 {
		t.Error("fetching the server's key logged into it")
	}
}

func TestFetchServerHostKeyWithoutJump(t *testing.T) {
	_, _, pub := newKeystore(t)
	srv := testutil.NewSSHServer(t, pub)
	info, err := sshx.FetchServerHostKey(context.Background(), opts(srv, nil, nil))
	if err != nil {
		t.Fatal(err)
	}
	if info.Hop != sshx.HopServer || info.Fingerprint != ssh.FingerprintSHA256(srv.HostSigner.PublicKey()) {
		t.Errorf("got %+v", info)
	}
}
