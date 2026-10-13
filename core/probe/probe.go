// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package probe is "Test connection" (DESIGN_BRIEF §5.2 step 5, handoff O6):
// a one-off check, without the VPN, that the server (and the jump host in
// front of it, if any) is reachable and trusted, accepts the key, forwards
// connections, and that the intranet DNS server answers through the tunnel.
package probe

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net/netip"
	"sync/atomic"
	"time"

	"github.com/miekg/dns"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/sshx"
)

// Check IDs, in the order they run. The jump checks come first and only
// for a profile with a jump host; Reachable then means the jump host reached
// the server.
const (
	JumpReachable = "jumpReachable"
	JumpIdentity  = "jumpIdentity"
	JumpAuth      = "jumpAuth"
	Reachable     = "reachable"
	Identity      = "identity"
	Auth          = "auth"
	Forwarding    = "forwarding"
	DNS           = "dns"
)

// Check outcomes. The first failure stops the rest (NotRun); Forwarding and
// DNS are Skipped when the profile has no intranet DNS server.
const (
	Passed  = "passed"
	Failed  = "failed"
	NotRun  = "notRun"
	Skipped = "skipped"
)

// Check is one row of the result.
type Check struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Ms     int64  `json:"ms,omitempty"`     // Reachable checks (TCP connect or channel open) and DNS (round trip)
	Target string `json:"target,omitempty"` // Forwarding and DNS: the resolver's ip:port
	Code   string `json:"code,omitempty"`   // Failed: an ARCHITECTURE §8 code
	Detail string `json:"detail,omitempty"` // Failed: for diagnostics, not UI copy
}

// dnsTimeout bounds the forwarding and DNS checks.
const dnsTimeout = 5 * time.Second

// checks is the result being filled in, addressed by check ID.
type checks []Check

func newChecks(ids ...string) checks {
	cs := make(checks, len(ids))
	for i, id := range ids {
		cs[i] = Check{ID: id, Status: NotRun}
	}
	return cs
}

func (cs checks) at(id string) *Check {
	for i := range cs {
		if cs[i].ID == id {
			return &cs[i]
		}
	}
	panic("probe: no check " + id)
}

func (cs checks) pass(id string) { cs.at(id).Status = Passed }

func (cs checks) fail(id string, err error) []Check {
	c := cs.at(id)
	c.Status = Failed
	c.Code = string(errcode.Of(err))
	c.Detail = err.Error()
	return cs
}

// Run runs the checks against prof. o is sshx.OptionsFor(prof, signer) with
// Protect and Lookup set; signerErr, if not nil, is why there is no signer
// (KEY_UNAVAILABLE) and fails the first Auth check.
func Run(ctx context.Context, prof *config.Profile, o sshx.Options, signerErr error) []Check {
	if o.Jump != nil {
		return runJump(ctx, prof, o, signerErr)
	}
	cs := newChecks(Reachable, Identity, Auth, Forwarding, DNS)
	if !ping(ctx, cs, Reachable, o) {
		return cs
	}
	c, ok := login(ctx, cs, Identity, Auth, prof.HostKey, signerErr, o)
	if !ok {
		return cs
	}
	defer c.Close()
	return resolver(ctx, cs, prof, c)
}

// runJump checks the jump host, then the server through it.
func runJump(ctx context.Context, prof *config.Profile, o sshx.Options, signerErr error) []Check {
	cs := newChecks(JumpReachable, JumpIdentity, JumpAuth, Reachable, Identity, Auth, Forwarding, DNS)
	jo := o.JumpOptions()
	if !ping(ctx, cs, JumpReachable, jo) {
		return cs
	}
	jc, ok := login(ctx, cs, JumpIdentity, JumpAuth, prof.Jump.HostKey, signerErr, jo)
	if !ok {
		return cs
	}
	defer jc.Close()

	// The server through the jump host: Reachable is the channel opening.
	// The server's own pin is checked by the dial, after the channel opened.
	var opened, identityOK atomic.Bool
	var openMs atomic.Int64
	start := time.Now()
	so := o
	so.Jump = nil
	so.OnStep = func(s sshx.Step) {
		switch s {
		case sshx.StepIdentity:
			openMs.Store(max(time.Since(start).Milliseconds(), 1))
			opened.Store(true)
		case sshx.StepAuth:
			identityOK.Store(true)
		}
	}
	c, err := sshx.DialVia(ctx, jc, so)
	switch {
	case err == nil:
	case !opened.Load():
		return cs.fail(Reachable, err)
	case !identityOK.Load():
		cs.at(Reachable).Ms = openMs.Load()
		cs.pass(Reachable)
		return cs.fail(Identity, err)
	default:
		cs.at(Reachable).Ms = openMs.Load()
		cs.pass(Reachable)
		cs.pass(Identity)
		return cs.fail(Auth, err)
	}
	defer c.Close()
	cs.at(Reachable).Ms = openMs.Load()
	cs.pass(Reachable)
	cs.pass(Identity)
	cs.pass(Auth)
	return resolver(ctx, cs, prof, c)
}

// ping is a Reachable check: resolve and TCP-connect, timed.
func ping(ctx context.Context, cs checks, id string, o sshx.Options) bool {
	d, err := sshx.Ping(ctx, o)
	if err != nil {
		cs.fail(id, err)
		return false
	}
	cs.at(id).Ms = max(d.Milliseconds(), 1)
	cs.pass(id)
	return true
}

// login is an Identity and an Auth check: it dials o directly and tells a
// host key failure apart from a refused key.
func login(ctx context.Context, cs checks, identity, auth string, pin *config.HostKey, signerErr error, o sshx.Options) (*sshx.Client, bool) {
	if pin == nil {
		cs.fail(identity, errcode.New(errcode.HostKeyUnverified, errors.New("no pinned host key")))
		return nil, false
	}
	if signerErr != nil {
		cs.pass(identity)
		cs.fail(auth, signerErr)
		return nil, false
	}
	// StepAuth (StepJump for the jump host) comes after the host key matched
	// the pin.
	var identityOK atomic.Bool
	o.OnStep = func(s sshx.Step) {
		if s == sshx.StepAuth || s == sshx.StepJump {
			identityOK.Store(true)
		}
	}
	c, err := sshx.Dial(ctx, o)
	if err != nil {
		if identityOK.Load() {
			cs.pass(identity)
			cs.fail(auth, err)
		} else {
			cs.fail(identity, err)
		}
		return nil, false
	}
	cs.pass(identity)
	cs.pass(auth)
	return c, true
}

// resolver is the Forwarding and DNS checks over c.
func resolver(ctx context.Context, cs checks, prof *config.Profile, c *sshx.Client) []Check {
	server := prof.DNSServer()
	if !server.IsValid() {
		cs.at(Forwarding).Status, cs.at(DNS).Status = Skipped, Skipped
		return cs
	}
	target := netip.AddrPortFrom(server, 53)
	cs.at(Forwarding).Target, cs.at(DNS).Target = target.String(), target.String()

	fctx, cancel := context.WithTimeout(ctx, dnsTimeout)
	defer cancel()
	conn, err := c.DialTCP(fctx, target)
	if err != nil {
		if errcode.Of(err) == errcode.ForwardingDenied {
			return cs.fail(Forwarding, err)
		}
		// The server tried and couldn't reach the resolver: forwarding works.
		cs.pass(Forwarding)
		return cs.fail(DNS, errcode.New(errcode.DNSUnreachable, err))
	}
	defer conn.Close()
	cs.pass(Forwarding)

	start := time.Now()
	if err := exchange(fctx, conn, probeQuery(prof)); err != nil {
		return cs.fail(DNS, errcode.New(errcode.DNSUnreachable, err))
	}
	cs.at(DNS).Ms = max(time.Since(start).Milliseconds(), 1)
	cs.pass(DNS)
	return cs
}

// probeQuery asks for the SOA of the first routed suffix, or the root NS
// set: any answer, even NXDOMAIN, proves the resolver is there.
func probeQuery(prof *config.Profile) *dns.Msg {
	m := new(dns.Msg)
	if len(prof.DNS.Suffixes) > 0 {
		m.SetQuestion(dns.Fqdn(prof.DNS.Suffixes[0]), dns.TypeSOA)
	} else {
		m.SetQuestion(".", dns.TypeNS)
	}
	return m
}

// exchange sends q over conn with RFC 7766 framing and waits for its reply.
func exchange(ctx context.Context, conn io.ReadWriteCloser, q *dns.Msg) error {
	raw, err := q.Pack()
	if err != nil {
		return err
	}
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	frame := binary.BigEndian.AppendUint16(nil, uint16(len(raw)))
	if _, err := conn.Write(append(frame, raw...)); err != nil {
		return err
	}
	for {
		var n [2]byte
		if _, err := io.ReadFull(conn, n[:]); err != nil {
			return ctxErr(ctx, err)
		}
		buf := make([]byte, binary.BigEndian.Uint16(n[:]))
		if _, err := io.ReadFull(conn, buf); err != nil {
			return ctxErr(ctx, err)
		}
		var r dns.Msg
		if err := r.Unpack(buf); err != nil {
			return fmt.Errorf("bad reply: %w", err)
		}
		if r.Id == q.Id && r.Response {
			return nil
		}
	}
}

func ctxErr(ctx context.Context, err error) error {
	if ctx.Err() != nil {
		return fmt.Errorf("no reply: %w", ctx.Err())
	}
	return err
}
