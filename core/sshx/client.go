// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package sshx is the SSH layer: protected dialing, authentication, host key
// pinning, keepalive, direct-tcpip, and route discovery (ARCHITECTURE §6).
package sshx

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/errcode"
)

// Step is a connect progress step shown while Connecting (handoff strings
// connecting_resolving / connecting_identity / connecting_auth).
type Step string

const (
	StepResolving Step = "resolving"
	StepIdentity  Step = "identity"
	StepJump      Step = "jump" // the jump host's key matched; reaching the server through it
	StepAuth      Step = "auth"
)

// Hops a host key or an error can belong to.
const (
	HopServer = "server"
	HopJump   = "jump"
)

// Hop is the jump host a connection goes through, like ssh -J.
type Hop struct {
	Host string
	Port int
	User string
	Pin  *config.HostKey // the jump host's pinned key; nil means unverified
}

// ProtectFunc excludes a socket from the VPN (VpnService.protect).
type ProtectFunc func(fd int) bool

// LookupFunc resolves a host name to IPv4 addresses without using the VPN.
type LookupFunc func(ctx context.Context, host string) ([]netip.Addr, error)

// Options configures Dial.
type Options struct {
	Host    string
	Port    int
	User    string
	Signer  ssh.Signer      // nil only for FetchHostKey
	Pin     *config.HostKey // pinned host key; nil means unverified
	Timeout time.Duration   // TCP connect + SSH handshake
	Protect ProtectFunc     // nil: sockets are not protected (tests, CLI)
	Lookup  LookupFunc      // nil: net.DefaultResolver
	OnStep  func(Step)      // optional progress callback
	// Jump, if set, is logged into first; Host is then reached and resolved
	// through it.
	Jump    *Hop
	hostKey func(ssh.PublicKey)
	hop     string // HopJump while dialing the jump host itself
}

// OptionsFor builds Options from a profile.
func OptionsFor(p *config.Profile, signer ssh.Signer) Options {
	o := Options{
		Host:    p.Server.Host,
		Port:    p.Server.Port,
		User:    p.Server.User,
		Signer:  signer,
		Pin:     p.HostKey,
		Timeout: p.ConnectTimeout(),
	}
	if j := p.Jump; j != nil {
		o.Jump = &Hop{Host: j.Host, Port: j.Port, User: p.JumpUser(), Pin: j.HostKey}
	}
	return o
}

// JumpOptions returns the options for dialing o's jump host on its own: its
// address, user, and pin, with o's key, socket protection, and lookup. Its
// StepAuth is reported as StepJump. o.Jump must be set.
func (o Options) JumpOptions() Options {
	j := o.Jump
	jo := Options{Host: j.Host, Port: j.Port, User: j.User, Signer: o.Signer, Pin: j.Pin,
		Timeout: o.Timeout, Protect: o.Protect, Lookup: o.Lookup, hop: HopJump}
	if o.OnStep != nil {
		jo.OnStep = func(s Step) {
			if s == StepAuth {
				s = StepJump
			}
			o.OnStep(s)
		}
	}
	return jo
}

func (o Options) addr() string { return net.JoinHostPort(o.Host, strconv.Itoa(o.Port)) }

// Client is an authenticated SSH connection.
type Client struct {
	*ssh.Client
	conn net.Conn
	jump *Client // the jump host connection this one runs inside, if any
}

// Close closes the connection and the jump host connection under it.
func (c *Client) Close() error {
	err := c.Client.Close()
	if c.jump != nil {
		c.jump.Close()
	}
	return err
}

var errProtect = errors.New("VpnService.protect failed")

// Dial connects, verifies the host key against the pin, and authenticates.
// With a jump host it does that for the jump host first and then for the
// server through it. Errors carry an errcode: AUTH_FAILED,
// HOST_KEY_UNVERIFIED, HOST_KEY_MISMATCH, KEY_UNAVAILABLE, FORWARDING_DENIED
// (the jump host refused to forward to the server), or HOST_UNREACHABLE for
// everything that may succeed on retry. Errors on the jump host name it.
func Dial(ctx context.Context, o Options) (*Client, error) {
	if o.Jump == nil {
		return dialDirect(ctx, o)
	}
	if o.Timeout <= 0 {
		o.Timeout = config.DefaultConnectTimeoutSec * time.Second
	}
	// One timeout for the whole dial, both hops.
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()
	jc, err := dialDirect(ctx, o.JumpOptions())
	if err != nil {
		return nil, &HopError{Hop: HopJump, Err: onJump(o.Jump, err)}
	}
	so := o
	if o.OnStep != nil {
		// The jump host already reported identity; the server's key
		// exchange is part of the jump step.
		so.OnStep = func(s Step) {
			if s != StepIdentity {
				o.OnStep(s)
			}
		}
	}
	c, err := DialVia(ctx, jc, so)
	if err != nil {
		jc.Close()
		return nil, &HopError{Hop: HopServer, Err: err}
	}
	return c, nil
}

// HopError says which host a failed dial through a jump host is about:
// HopJump for the jump host itself, HopServer for reaching the server
// through it or logging into the server. Its code is Err's.
type HopError struct {
	Hop string
	Err error
}

func (e *HopError) Error() string { return e.Err.Error() }
func (e *HopError) Unwrap() error { return e.Err }

// HopOf returns err's HopError hop, or "" (a dial without a jump host).
func HopOf(err error) string {
	var he *HopError
	if errors.As(err, &he) {
		return he.Hop
	}
	return ""
}

// onJump names the jump host in err's detail, keeping its code.
func onJump(j *Hop, err error) error {
	where := "jump host " + net.JoinHostPort(j.Host, strconv.Itoa(j.Port))
	var ce *errcode.Error
	if errors.As(err, &ce) {
		return errcode.New(ce.Code, fmt.Errorf("%s: %w", where, ce.Err))
	}
	return fmt.Errorf("%s: %w", where, err)
}

// DialVia reaches o's server through an authenticated connection to a jump
// host, then verifies the server's key and authenticates as Dial does. The
// server's host name is resolved by the jump host. On success the returned
// Client owns via and closes it; on failure the caller still owns it. It
// reports StepIdentity once the jump host opened the channel, and StepAuth
// once the server's key matched.
func DialVia(ctx context.Context, via *Client, o Options) (*Client, error) {
	step := func(s Step) {
		if o.OnStep != nil {
			o.OnStep(s)
		}
	}
	if o.Timeout <= 0 {
		o.Timeout = config.DefaultConnectTimeoutSec * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()

	addr := o.addr()
	conn, err := via.DialContext(ctx, "tcp", addr)
	if err != nil {
		var oce *ssh.OpenChannelError
		switch {
		case errors.As(err, &oce) && oce.Reason == ssh.Prohibited:
			return nil, errcode.New(errcode.ForwardingDenied, fmt.Errorf("jump host refused to forward to %s: %w", addr, err))
		case ctx.Err() != nil:
			return nil, errcode.New(errcode.HostUnreachable, fmt.Errorf("jump host didn't reach %s in time: %w", addr, ctx.Err()))
		default:
			return nil, errcode.New(errcode.HostUnreachable, fmt.Errorf("jump host couldn't reach %s: %w", addr, err))
		}
	}
	step(StepIdentity)
	// A channel has no deadlines: closing it ends a stuck handshake.
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	c, chans, reqs, err := ssh.NewClientConn(conn, addr, clientConfig(o, step))
	if err != nil {
		conn.Close()
		return nil, classifyHandshake(ctx, err)
	}
	if !stop() {
		c.Close()
		return nil, errcode.New(errcode.HostUnreachable, ctx.Err())
	}
	return &Client{Client: ssh.NewClient(c, chans, reqs), conn: conn, jump: via}, nil
}

// clientConfig authenticates with o's key and checks o's pin, reporting
// StepAuth once the host key matched.
func clientConfig(o Options, step func(Step)) *ssh.ClientConfig {
	var auth []ssh.AuthMethod
	if o.Signer != nil {
		auth = []ssh.AuthMethod{ssh.PublicKeys(o.Signer)}
	}
	hop := o.hop
	if hop == "" {
		hop = HopServer
	}
	pinned := pinnedCallback(o.Pin, o.hostKey, hop)
	cfg := &ssh.ClientConfig{
		User: o.User,
		Auth: auth,
		HostKeyCallback: func(h string, a net.Addr, k ssh.PublicKey) error {
			if err := pinned(h, a, k); err != nil {
				return err
			}
			step(StepAuth)
			return nil
		},
		Timeout: o.Timeout,
	}
	if o.Pin != nil && o.Pin.Type != "" {
		// Ask for the pinned algorithm first so servers with several host keys
		// present the one we trust.
		cfg.HostKeyAlgorithms = hostKeyAlgorithms(o.Pin.Type)
	}
	return cfg
}

// dialDirect is Dial without a jump host.
func dialDirect(ctx context.Context, o Options) (*Client, error) {
	step := func(s Step) {
		if o.OnStep != nil {
			o.OnStep(s)
		}
	}
	if o.Timeout <= 0 {
		o.Timeout = config.DefaultConnectTimeoutSec * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()

	step(StepResolving)
	addrs, err := resolve(ctx, o)
	if err != nil {
		return nil, errcode.New(errcode.HostUnreachable, err)
	}

	step(StepIdentity)
	conn, err := dialAny(ctx, addrs, o.Port, o.Protect)
	if err != nil {
		return nil, errcode.New(errcode.HostUnreachable, err)
	}

	// ssh.NewClientConn has no context: bound it with a deadline and close the
	// socket if ctx ends first.
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()

	addr := o.addr()
	c, chans, reqs, err := ssh.NewClientConn(conn, addr, clientConfig(o, step))
	if err != nil {
		conn.Close()
		return nil, classifyHandshake(ctx, err)
	}
	if !stop() {
		c.Close()
		return nil, errcode.New(errcode.HostUnreachable, ctx.Err())
	}
	_ = conn.SetDeadline(time.Time{})
	return &Client{Client: ssh.NewClient(c, chans, reqs), conn: conn}, nil
}

// hostKeyAlgorithms returns the signature algorithms for a host key type.
// RSA keys are offered with SHA-2 signatures only.
func hostKeyAlgorithms(keyType string) []string {
	if keyType == ssh.KeyAlgoRSA {
		return []string{ssh.KeyAlgoRSASHA512, ssh.KeyAlgoRSASHA256}
	}
	return []string{keyType}
}

func classifyHandshake(ctx context.Context, err error) error {
	var ce *errcode.Error
	if errors.As(err, &ce) {
		return ce
	}
	if strings.Contains(err.Error(), "unable to authenticate") {
		return errcode.New(errcode.AuthFailed, err)
	}
	if ctx.Err() != nil {
		err = fmt.Errorf("%w (%v)", err, ctx.Err())
	}
	return errcode.New(errcode.HostUnreachable, err)
}

func resolve(ctx context.Context, o Options) ([]netip.Addr, error) {
	if a, err := netip.ParseAddr(o.Host); err == nil {
		return []netip.Addr{a.Unmap()}, nil
	}
	lookup := o.Lookup
	if lookup == nil {
		lookup = func(ctx context.Context, host string) ([]netip.Addr, error) {
			return net.DefaultResolver.LookupNetIP(ctx, "ip4", host)
		}
	}
	addrs, err := lookup(ctx, o.Host)
	if err != nil {
		return nil, fmt.Errorf("resolve %s: %w", o.Host, err)
	}
	if len(addrs) == 0 {
		return nil, fmt.Errorf("resolve %s: no addresses", o.Host)
	}
	return addrs, nil
}

func dialAny(ctx context.Context, addrs []netip.Addr, port int, protect ProtectFunc) (net.Conn, error) {
	d := net.Dialer{KeepAlive: 30 * time.Second}
	if protect != nil {
		d.Control = func(_, _ string, rc syscall.RawConn) error {
			ok := false
			if err := rc.Control(func(fd uintptr) { ok = protect(int(fd)) }); err != nil {
				return err
			}
			if !ok {
				return errProtect
			}
			return nil
		}
	}
	var firstErr error
	for _, a := range addrs {
		c, err := d.DialContext(ctx, "tcp", netip.AddrPortFrom(a, uint16(port)).String())
		if err == nil {
			return c, nil
		}
		if firstErr == nil {
			firstErr = err
		}
		if ctx.Err() != nil {
			break
		}
	}
	return nil, firstErr
}

// FetchHostKey connects to the first hop, the jump host if o has one, and
// returns its host key without authenticating: the host key callback aborts
// the handshake.
func FetchHostKey(ctx context.Context, o Options) (HostKeyInfo, error) {
	hop := HopServer
	if o.Jump != nil {
		o, hop = o.JumpOptions(), HopJump
	}
	var got ssh.PublicKey
	o.Signer = nil
	o.Pin = nil
	o.hostKey = func(k ssh.PublicKey) { got = k }
	_, err := dialDirect(ctx, o)
	if got != nil {
		return infoOf(got, hop), nil
	}
	if err == nil {
		err = errors.New("server presented no host key")
	}
	if hop == HopJump {
		err = onJump(&Hop{Host: o.Host, Port: o.Port}, err)
	}
	return HostKeyInfo{}, err
}

// FetchServerHostKey returns the server's host key. With a jump host it logs
// into the jump host (pinned key and o's key required) and fetches the
// server's key through it; without one it is FetchHostKey.
func FetchServerHostKey(ctx context.Context, o Options) (HostKeyInfo, error) {
	if o.Jump == nil {
		return FetchHostKey(ctx, o)
	}
	if o.Timeout <= 0 {
		o.Timeout = config.DefaultConnectTimeoutSec * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()
	jc, err := dialDirect(ctx, o.JumpOptions())
	if err != nil {
		return HostKeyInfo{}, onJump(o.Jump, err)
	}
	defer jc.Close()
	var got ssh.PublicKey
	o.Signer = nil
	o.Pin = nil
	o.hostKey = func(k ssh.PublicKey) { got = k }
	c, err := DialVia(ctx, jc, o)
	if c != nil {
		c.Close()
	}
	if got != nil {
		return infoOf(got, HopServer), nil
	}
	if err == nil {
		err = errors.New("server presented no host key")
	}
	return HostKeyInfo{}, err
}

// DialTCP opens a direct-tcpip channel to dst. Errors carry
// FORWARDING_DENIED, DEST_UNREACHABLE, DEST_TIMEOUT, or TUNNEL_DOWN.
func (c *Client) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	conn, err := c.DialContext(ctx, "tcp", dst.String())
	if err == nil {
		return conn, nil
	}
	return nil, ClassifyChannelError(err)
}

// ClassifyChannelError maps a direct-tcpip failure to a flow reason
// (ARCHITECTURE §4 step 3).
func ClassifyChannelError(err error) error {
	var oce *ssh.OpenChannelError
	switch {
	case errors.As(err, &oce) && oce.Reason == ssh.Prohibited:
		return errcode.New(errcode.ForwardingDenied, err)
	case errors.As(err, &oce):
		return errcode.New(errcode.DestUnreachable, err)
	case errors.Is(err, context.DeadlineExceeded):
		return errcode.New(errcode.DestTimeout, err)
	default:
		return errcode.New(errcode.TunnelDown, err)
	}
}

// ErrKeepaliveTimeout is returned by Keepalive when the server doesn't answer.
var ErrKeepaliveTimeout = errors.New("keepalive timed out")

// Keepalive sends keepalive@openssh.com every interval and returns when a
// request fails or isn't answered within 2 × interval, or nil when ctx ends.
func (c *Client) Keepalive(ctx context.Context, interval time.Duration) error {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
		}
		done := make(chan error, 1)
		go func() {
			// OpenSSH answers unknown global requests with REQUEST_FAILURE,
			// which is still proof of life.
			_, _, err := c.SendRequest("keepalive@openssh.com", true, nil)
			done <- err
		}()
		timer := time.NewTimer(2 * interval)
		select {
		case err := <-done:
			timer.Stop()
			if err != nil {
				return fmt.Errorf("keepalive: %w", err)
			}
		case <-timer.C:
			return ErrKeepaliveTimeout
		case <-ctx.Done():
			timer.Stop()
			return nil
		}
	}
}

// Ping resolves the server and opens (then closes) a TCP connection to it,
// returning how long the connect took. Errors carry HOST_UNREACHABLE.
func Ping(ctx context.Context, o Options) (time.Duration, error) {
	if o.Timeout <= 0 {
		o.Timeout = config.DefaultConnectTimeoutSec * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()
	addrs, err := resolve(ctx, o)
	if err != nil {
		return 0, errcode.New(errcode.HostUnreachable, err)
	}
	start := time.Now()
	conn, err := dialAny(ctx, addrs, o.Port, o.Protect)
	if err != nil {
		return 0, errcode.New(errcode.HostUnreachable, err)
	}
	d := time.Since(start)
	conn.Close()
	return d, nil
}
