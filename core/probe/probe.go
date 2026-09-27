// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package probe is "Test connection" (DESIGN_BRIEF §5.2 step 5, handoff O6):
// a one-off check, without the VPN, that the server is reachable and trusted,
// accepts the key, forwards connections, and that the intranet DNS server
// answers through the tunnel.
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

// Check IDs, in the order they run.
const (
	Reachable  = "reachable"
	Identity   = "identity"
	Auth       = "auth"
	Forwarding = "forwarding"
	DNS        = "dns"
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
	Ms     int64  `json:"ms,omitempty"`     // Reachable (TCP connect) and DNS (round trip)
	Target string `json:"target,omitempty"` // Forwarding and DNS: the resolver's ip:port
	Code   string `json:"code,omitempty"`   // Failed: an ARCHITECTURE §8 code
	Detail string `json:"detail,omitempty"` // Failed: for diagnostics, not UI copy
}

// dnsTimeout bounds the forwarding and DNS checks.
const dnsTimeout = 5 * time.Second

// Run runs the checks against prof. o is sshx.OptionsFor(prof, signer) with
// Protect and Lookup set; signerErr, if not nil, is why there is no signer
// (KEY_UNAVAILABLE) and fails the Auth check.
func Run(ctx context.Context, prof *config.Profile, o sshx.Options, signerErr error) []Check {
	checks := []Check{{ID: Reachable}, {ID: Identity}, {ID: Auth}, {ID: Forwarding}, {ID: DNS}}
	for i := range checks {
		checks[i].Status = NotRun
	}
	fail := func(i int, err error) []Check {
		checks[i].Status = Failed
		checks[i].Code = string(errcode.Of(err))
		checks[i].Detail = err.Error()
		return checks
	}
	pass := func(i int) { checks[i].Status = Passed }

	d, err := sshx.Ping(ctx, o)
	if err != nil {
		return fail(0, err)
	}
	checks[0].Ms = max(d.Milliseconds(), 1)
	pass(0)

	if prof.HostKey == nil {
		return fail(1, errcode.New(errcode.HostKeyUnverified, errors.New("no pinned host key")))
	}
	if signerErr != nil {
		pass(1)
		return fail(2, signerErr)
	}
	// StepAuth comes after the host key matched the pin.
	var identityOK atomic.Bool
	o.OnStep = func(s sshx.Step) {
		if s == sshx.StepAuth {
			identityOK.Store(true)
		}
	}
	c, err := sshx.Dial(ctx, o)
	if err != nil {
		if identityOK.Load() {
			pass(1)
			return fail(2, err)
		}
		return fail(1, err)
	}
	defer c.Close()
	pass(1)
	pass(2)

	server := prof.DNSServer()
	if !server.IsValid() {
		checks[3].Status, checks[4].Status = Skipped, Skipped
		return checks
	}
	target := netip.AddrPortFrom(server, 53)
	checks[3].Target, checks[4].Target = target.String(), target.String()

	fctx, cancel := context.WithTimeout(ctx, dnsTimeout)
	defer cancel()
	conn, err := c.DialTCP(fctx, target)
	if err != nil {
		if errcode.Of(err) == errcode.ForwardingDenied {
			return fail(3, err)
		}
		// The server tried and couldn't reach the resolver: forwarding works.
		pass(3)
		return fail(4, errcode.New(errcode.DNSUnreachable, err))
	}
	defer conn.Close()
	pass(3)

	start := time.Now()
	if err := exchange(fctx, conn, probeQuery(prof)); err != nil {
		return fail(4, errcode.New(errcode.DNSUnreachable, err))
	}
	checks[4].Ms = max(time.Since(start).Milliseconds(), 1)
	pass(4)
	return checks
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
