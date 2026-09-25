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
	StepAuth      Step = "auth"
)

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
	hostKey func(ssh.PublicKey)
}

// OptionsFor builds Options from a profile.
func OptionsFor(p *config.Profile, signer ssh.Signer) Options {
	return Options{
		Host:    p.Server.Host,
		Port:    p.Server.Port,
		User:    p.Server.User,
		Signer:  signer,
		Pin:     p.HostKey,
		Timeout: p.ConnectTimeout(),
	}
}

// Client is an authenticated SSH connection.
type Client struct {
	*ssh.Client
	conn net.Conn
}

var errProtect = errors.New("VpnService.protect failed")

// Dial connects, verifies the host key against the pin, and authenticates.
// Errors carry an errcode: AUTH_FAILED, HOST_KEY_UNVERIFIED,
// HOST_KEY_MISMATCH, KEY_UNAVAILABLE, or HOST_UNREACHABLE for everything
// that may succeed on retry.
func Dial(ctx context.Context, o Options) (*Client, error) {
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

	var auth []ssh.AuthMethod
	if o.Signer != nil {
		auth = []ssh.AuthMethod{ssh.PublicKeys(o.Signer)}
	}
	pinned := pinnedCallback(o.Pin, o.hostKey)
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
	addr := net.JoinHostPort(o.Host, strconv.Itoa(o.Port))
	c, chans, reqs, err := ssh.NewClientConn(conn, addr, cfg)
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

// FetchHostKey connects and returns the server's host key without
// authenticating: the host key callback aborts the handshake.
func FetchHostKey(ctx context.Context, o Options) (HostKeyInfo, error) {
	var got ssh.PublicKey
	o.Signer = nil
	o.Pin = nil
	o.hostKey = func(k ssh.PublicKey) { got = k }
	_, err := Dial(ctx, o)
	if got != nil {
		return infoOf(got), nil
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
