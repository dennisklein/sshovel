// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package testutil provides in-process test peers: an SSH server, an
// intranet DNS server, and a gVisor stack that plays the apps.
package testutil

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// SSHServer is an x/crypto/ssh server that forwards direct-tcpip channels
// and runs canned exec commands.
type SSHServer struct {
	Addr       string // 127.0.0.1:port
	Port       int
	HostSigner ssh.Signer

	// Dial connects forwarded channels. Default: net.Dial to the requested
	// address. Tests map intranet addresses to local listeners here.
	Dial func(addr string) (net.Conn, error)
	// Exec maps a command line to its stdout. Unknown commands exit 127.
	Exec map[string]string

	ProhibitForwarding atomic.Bool // reject direct-tcpip with Prohibited
	DenyExec           atomic.Bool // refuse exec requests

	Accepts    atomic.Int64 // TCP connections accepted
	Handshakes atomic.Int64 // completed authentications
	Channels   atomic.Int64 // accepted direct-tcpip channels

	ln     net.Listener
	mu     sync.Mutex
	keys   []ssh.PublicKey
	conns  map[*stallConn]bool
	stall  bool
	stallC chan struct{}
	wg     sync.WaitGroup
}

// NewSSHServer starts a server with an Ed25519 host key that accepts the
// given public keys. It is closed when the test ends.
func NewSSHServer(t testing.TB, authorized ...ssh.PublicKey) *SSHServer {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hs, err := ssh.NewSignerFromKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	return NewSSHServerWithHostKey(t, hs, authorized...)
}

// NewSSHServerWithHostKey is NewSSHServer with a given host key.
func NewSSHServerWithHostKey(t testing.TB, hostKey ssh.Signer, authorized ...ssh.PublicKey) *SSHServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &SSHServer{
		Addr:       ln.Addr().String(),
		Port:       ln.Addr().(*net.TCPAddr).Port,
		HostSigner: hostKey,
		Exec:       map[string]string{},
		ln:         ln,
		keys:       authorized,
		conns:      map[*stallConn]bool{},
		stallC:     make(chan struct{}),
	}
	s.Dial = func(addr string) (net.Conn, error) { return net.DialTimeout("tcp", addr, 5*time.Second) }
	s.wg.Add(1)
	go s.serve()
	t.Cleanup(s.Close)
	return s
}

// Authorize adds a client key.
func (s *SSHServer) Authorize(k ssh.PublicKey) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.keys = append(s.keys, k)
}

// Close stops the server and drops all connections.
func (s *SSHServer) Close() {
	s.ln.Close()
	s.KillConnections()
	s.Resume()
	s.wg.Wait()
}

// KillConnections drops every client connection (the client sees EOF).
func (s *SSHServer) KillConnections() {
	s.mu.Lock()
	cs := make([]*stallConn, 0, len(s.conns))
	for c := range s.conns {
		cs = append(cs, c)
	}
	s.mu.Unlock()
	for _, c := range cs {
		c.Close()
	}
}

// Stall stops reading from existing and new connections, like a peer that
// silently vanished. Resume undoes it.
func (s *SSHServer) Stall() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.stall = true
}

func (s *SSHServer) Resume() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stall {
		s.stall = false
		close(s.stallC)
		s.stallC = make(chan struct{})
	}
}

func (s *SSHServer) stalled() (bool, chan struct{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stall, s.stallC
}

// stallConn blocks reads while the server is stalled.
type stallConn struct {
	net.Conn
	s *SSHServer
}

func (c *stallConn) Read(p []byte) (int, error) {
	for {
		st, ch := c.s.stalled()
		if !st {
			return c.Conn.Read(p)
		}
		<-ch
	}
}

func (s *SSHServer) config() *ssh.ServerConfig {
	cfg := &ssh.ServerConfig{
		PublicKeyCallback: func(_ ssh.ConnMetadata, k ssh.PublicKey) (*ssh.Permissions, error) {
			s.mu.Lock()
			defer s.mu.Unlock()
			for _, a := range s.keys {
				if string(a.Marshal()) == string(k.Marshal()) {
					return nil, nil
				}
			}
			return nil, errors.New("unknown key")
		},
	}
	cfg.AddHostKey(s.HostSigner)
	return cfg
}

func (s *SSHServer) serve() {
	defer s.wg.Done()
	for {
		nc, err := s.ln.Accept()
		if err != nil {
			return
		}
		s.Accepts.Add(1)
		c := &stallConn{Conn: nc, s: s}
		s.mu.Lock()
		s.conns[c] = true
		s.mu.Unlock()
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer func() {
				c.Close()
				s.mu.Lock()
				delete(s.conns, c)
				s.mu.Unlock()
			}()
			s.handle(c)
		}()
	}
}

func (s *SSHServer) handle(c net.Conn) {
	sc, chans, reqs, err := ssh.NewServerConn(c, s.config())
	if err != nil {
		return
	}
	defer sc.Close()
	s.Handshakes.Add(1)
	go func() {
		for r := range reqs {
			// Like OpenSSH: unknown global requests (keepalive@openssh.com)
			// get REQUEST_FAILURE.
			if r.WantReply {
				_ = r.Reply(false, nil)
			}
		}
	}()
	var wg sync.WaitGroup
	defer wg.Wait()
	for nc := range chans {
		switch nc.ChannelType() {
		case "direct-tcpip":
			wg.Add(1)
			go func() { defer wg.Done(); s.directTCPIP(nc) }()
		case "session":
			wg.Add(1)
			go func() { defer wg.Done(); s.session(nc) }()
		default:
			_ = nc.Reject(ssh.UnknownChannelType, "unsupported")
		}
	}
}

func (s *SSHServer) directTCPIP(nc ssh.NewChannel) {
	var p struct {
		Host     string
		Port     uint32
		OrigHost string
		OrigPort uint32
	}
	if err := ssh.Unmarshal(nc.ExtraData(), &p); err != nil {
		_ = nc.Reject(ssh.ConnectionFailed, "bad payload")
		return
	}
	if s.ProhibitForwarding.Load() {
		_ = nc.Reject(ssh.Prohibited, "administratively prohibited")
		return
	}
	up, err := s.Dial(net.JoinHostPort(p.Host, strconv.Itoa(int(p.Port))))
	if err != nil {
		_ = nc.Reject(ssh.ConnectionFailed, err.Error())
		return
	}
	ch, reqs, err := nc.Accept()
	if err != nil {
		up.Close()
		return
	}
	s.Channels.Add(1)
	go ssh.DiscardRequests(reqs)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(ch, up)
		_ = ch.CloseWrite()
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(up, ch)
		if tc, ok := up.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		} else {
			up.Close()
		}
	}()
	wg.Wait()
	ch.Close()
	up.Close()
}

func (s *SSHServer) session(nc ssh.NewChannel) {
	ch, reqs, err := nc.Accept()
	if err != nil {
		return
	}
	defer ch.Close()
	for r := range reqs {
		if r.Type != "exec" {
			if r.WantReply {
				_ = r.Reply(false, nil)
			}
			continue
		}
		if s.DenyExec.Load() {
			_ = r.Reply(false, nil)
			continue
		}
		var cmd struct{ Command string }
		_ = ssh.Unmarshal(r.Payload, &cmd)
		_ = r.Reply(true, nil)
		out, ok := s.Exec[cmd.Command]
		status := uint32(0)
		if ok {
			_, _ = io.WriteString(ch, out)
		} else {
			_, _ = fmt.Fprintf(ch.Stderr(), "sh: %s: not found\n", cmd.Command)
			status = 127
		}
		_, _ = ch.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{status}))
		return
	}
}

// NewECDSAKey returns a P-256 key, the kind Android Keystore produces.
func NewECDSAKey(t testing.TB) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}
