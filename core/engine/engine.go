// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package engine owns a tunnel's lifecycle: the state machine, reconnect with
// backoff, keepalive, the netstack, the DNS proxy, and stats
// (ARCHITECTURE §7).
package engine

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"net"
	"net/netip"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/dnsproxy"
	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/netstack"
	"github.com/dennisklein/sshovel/core/sshx"
)

// State names, shared with Kotlin's TunnelState.
type State string

const (
	Off            State = "off"
	Connecting     State = "connecting"
	SSHReady       State = "sshReady" // authenticated, waiting for AttachTun
	On             State = "on"
	Reconnecting   State = "reconnecting"
	NeedsAttention State = "needsAttention"
	Disconnecting  State = "disconnecting"
)

// Why a reconnect is happening (handoff strings reconnect_detail_*).
const (
	ReasonDialFailed     = "dialFailed"
	ReasonNetworkChanged = "networkChanged"
	ReasonKeepalive      = "keepaliveTimeout"
	ReasonClosed         = "connectionClosed"
)

// StepTunnel is the last Connecting step, after sshx's steps.
const StepTunnel = "tunnel"

// Status is what OnState reports. Only State is always set.
type Status struct {
	State       State    `json:"state"`
	Code        string   `json:"code"`
	Detail      string   `json:"detail"`
	Step        string   `json:"step,omitempty"`        // Connecting: resolving, identity, auth, tunnel
	Reason      string   `json:"reason,omitempty"`      // Reconnecting
	Attempt     int      `json:"attempt,omitempty"`     // Reconnecting
	NextRetryAt int64    `json:"nextRetryAt,omitempty"` // Reconnecting, unix ms; 0 while dialing
	Warnings    []string `json:"warnings,omitempty"`    // On: FORWARDING_DENIED, DNS_UNREACHABLE
	// NeedsAttention with HOST_KEY_UNVERIFIED or HOST_KEY_MISMATCH: the key
	// the server presented. Never used to pin anything automatically.
	HostKey *sshx.HostKeyInfo `json:"hostKey,omitempty"`
}

// Log levels passed to Callbacks.Log.
const (
	LevelDebug = 0
	LevelInfo  = 1
	LevelWarn  = 2
	LevelError = 3
)

// Log components (Diagnostics filter chips).
const (
	CompSSH    = "SSH"
	CompDNS    = "DNS"
	CompTunnel = "Tunnel"
)

// Callbacks connect the engine to its host (Kotlin via mobile, or the CLI).
// Every field is optional.
type Callbacks struct {
	Protect    func(fd int) bool
	SignDigest sshx.SignDigestFunc
	// Upstream resolves raw DNS on the underlying network. If nil, direct
	// queries fail and the jump host is resolved with the Go resolver.
	Upstream   dnsproxy.UpstreamFunc
	OnState    func(Status)
	Log        func(level int, component, msg string)
	OnDNSEvent func(dnsproxy.Event)
	OnFlow     func(netstack.FlowEvent)
}

// Params configures an Engine.
type Params struct {
	Profile   *config.Profile
	Signer    ssh.Signer
	Callbacks Callbacks
	Clock     Clock          // default: real time
	Rand      func() float64 // jitter source in [0,1); default math/rand
	// Keepalive overrides the profile's keepalive interval (tests).
	Keepalive time.Duration
}

// Engine runs one tunnel. Create it with New, then Start it once.
type Engine struct {
	p         *config.Profile
	keepalive time.Duration
	sshOpts   sshx.Options
	cb        Callbacks
	clock     Clock
	rand      func() float64
	dns       *dnsproxy.Proxy

	ctx    context.Context
	cancel context.CancelFunc
	done   chan struct{}

	client atomic.Pointer[sshx.Client]
	notify *notifier

	mu          sync.Mutex
	status      Status
	phase       phase
	phaseCancel context.CancelCauseFunc
	link        stack.LinkEndpoint
	linkReady   chan struct{} // closed once a link is attached
	ns          *netstack.Stack
	onSince     time.Time
	lastError   string
	warnings    map[string]bool
	started     bool
	running     bool // run loop started
	stopped     bool
}

type phase int

const (
	phaseIdle phase = iota
	phaseDial
	phaseBackoff
	phaseSSHReady
	phaseOn
	phaseAttention
)

var (
	errNetworkChanged = errors.New("network changed")
	errRetryNow       = errors.New("retry requested")
	errConnClosed     = errors.New("connection closed")
)

// New creates an engine.
func New(pr Params) *Engine {
	e := &Engine{
		p:         pr.Profile,
		keepalive: pr.Keepalive,
		cb:        pr.Callbacks,
		clock:     pr.Clock,
		rand:      pr.Rand,
		done:      make(chan struct{}),
		linkReady: make(chan struct{}),
		warnings:  map[string]bool{},
		status:    Status{State: Off},
	}
	if e.clock == nil {
		e.clock = realClock{}
	}
	if e.rand == nil {
		e.rand = rand.Float64
	}
	if e.keepalive <= 0 {
		e.keepalive = pr.Profile.Keepalive()
	}
	e.ctx, e.cancel = context.WithCancel(context.Background())
	e.notify = newNotifier(pr.Callbacks.OnState)

	e.sshOpts = sshx.OptionsFor(pr.Profile, pr.Signer)
	e.sshOpts.Protect = pr.Callbacks.Protect
	if up := pr.Callbacks.Upstream; up != nil {
		e.sshOpts.Lookup = func(ctx context.Context, host string) ([]netip.Addr, error) {
			return dnsproxy.LookupIPv4(ctx, up, host)
		}
	}

	e.dns = dnsproxy.New(dnsproxy.Config{
		Server:         pr.Profile.DNSServer(),
		Suffixes:       pr.Profile.DNS.Suffixes,
		ReverseLookups: pr.Profile.DNS.ReverseLookups,
		Routes:         pr.Profile.RoutePrefixes(),
		HideAAAA:       pr.Profile.DNS.HideAAAA,
	}, e.dialTCP, pr.Callbacks.Upstream)
	e.dns.OnEvent = pr.Callbacks.OnDNSEvent
	e.dns.OnTunnelHealth = func(ok bool) {
		if !ok && e.client.Load() == nil {
			return // the tunnel is down, not the resolver
		}
		if !ok {
			e.log(LevelWarn, CompDNS, "intranet DNS server is not answering through the tunnel")
		}
		e.setWarning(errcode.DNSUnreachable, !ok)
	}
	return e
}

// Start begins connecting in the background. link may be nil, in which case
// the engine reports SSHReady after authenticating and waits for AttachLink.
func (e *Engine) Start(link stack.LinkEndpoint) error {
	e.mu.Lock()
	if e.started {
		e.mu.Unlock()
		return errors.New("engine already started")
	}
	e.started = true
	e.mu.Unlock()
	if link != nil {
		if err := e.AttachLink(link); err != nil {
			return err
		}
	}
	e.mu.Lock()
	e.running = true
	e.mu.Unlock()
	go e.run()
	return nil
}

// AttachLink hands the engine the TUN link. It may be called once, before or
// after SSH is ready.
func (e *Engine) AttachLink(link stack.LinkEndpoint) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.link != nil {
		return errors.New("link already attached")
	}
	if e.ctx.Err() != nil {
		link.Close()
		return errors.New("engine stopped")
	}
	ns, err := netstack.New(link, netstack.Options{
		MTU:            uint32(e.p.Tun.MTU),
		TunPrefix:      e.p.TunPrefix(),
		DNSVirtualIP:   e.p.DNSVirtualIP(),
		ConnectTimeout: e.p.ConnectTimeout(),
		Dial:           e.dialTCP,
		DNS:            e.dns,
		OnFlow:         e.onFlow,
	})
	if err != nil {
		link.Close()
		return err
	}
	e.link, e.ns = link, ns
	close(e.linkReady)
	return nil
}

// Stop tears everything down and returns once the engine is Off.
//
// The final "off" status is delivered to OnState right after, in order; an
// OnState handler may itself call Stop.
func (e *Engine) Stop() {
	e.mu.Lock()
	running, stopped := e.running, e.stopped
	e.started, e.stopped = true, true // a stopped engine can't be started
	e.mu.Unlock()
	e.cancel()
	if running {
		<-e.done
	} else if !stopped {
		e.teardown()
	}
	e.notify.stop()
}

// NetworkChanged reconnects immediately: it cancels a pending backoff or
// dial, and drops a live connection that is bound to the old network.
func (e *Engine) NetworkChanged() {
	e.interrupt(errNetworkChanged, phaseDial, phaseBackoff, phaseOn, phaseSSHReady)
}

// RetryNow skips the backoff wait, or retries after NeedsAttention.
func (e *Engine) RetryNow() {
	e.interrupt(errRetryNow, phaseBackoff, phaseAttention)
}

func (e *Engine) interrupt(cause error, phases ...phase) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.phaseCancel != nil && slices.Contains(phases, e.phase) {
		e.phaseCancel(cause)
	}
}

// Status returns the current status.
func (e *Engine) Status() Status {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.status
}

// Stats is the stats snapshot (ARCHITECTURE §7).
type Stats struct {
	UptimeSec   int64  `json:"uptimeSec"`
	BytesIn     uint64 `json:"bytesIn"`
	BytesOut    uint64 `json:"bytesOut"`
	ActiveFlows int64  `json:"activeFlows"`
	DNSTunneled uint64 `json:"dnsTunneled"`
	DNSDirect   uint64 `json:"dnsDirect"`
	DroppedUDP  uint64 `json:"droppedUdp"`
	DroppedICMP uint64 `json:"droppedIcmp"`
	LastError   string `json:"lastError"`
}

func (e *Engine) Stats() Stats {
	e.mu.Lock()
	ns, since, lastErr, state := e.ns, e.onSince, e.lastError, e.status.State
	e.mu.Unlock()
	var s Stats
	if state == On && !since.IsZero() {
		s.UptimeSec = int64(e.clock.Now().Sub(since) / time.Second)
	}
	if ns != nil {
		st := ns.Stats()
		s.BytesIn, s.BytesOut, s.ActiveFlows = st.BytesIn, st.BytesOut, st.ActiveFlows
		s.DroppedUDP, s.DroppedICMP = st.DroppedUDP, st.DroppedICMP
	}
	s.DNSTunneled, s.DNSDirect = e.dns.Counts()
	s.LastError = lastErr
	return s
}

func (e *Engine) log(level int, comp, msg string) {
	if e.cb.Log != nil {
		e.cb.Log(level, comp, msg)
	}
}

// setStatus publishes a new status; warnings are carried over.
func (e *Engine) setStatus(s Status) {
	e.mu.Lock()
	if s.State == On && e.status.State != On {
		e.onSince = e.clock.Now()
	}
	if s.State == On {
		s.Warnings = e.warningList()
	}
	if s.Code != "" {
		e.lastError = s.Code
	}
	if !sameStatus(e.status, s) {
		e.status = s
		e.notify.push(s)
	}
	e.mu.Unlock()
}

func sameStatus(a, b Status) bool {
	return a.State == b.State && a.Code == b.Code && a.Detail == b.Detail && a.Step == b.Step &&
		a.Reason == b.Reason && a.Attempt == b.Attempt && a.NextRetryAt == b.NextRetryAt &&
		slices.Equal(a.Warnings, b.Warnings) &&
		(a.HostKey == nil) == (b.HostKey == nil) && (a.HostKey == nil || *a.HostKey == *b.HostKey)
}

func (e *Engine) warningList() []string {
	var ws []string
	for _, c := range []errcode.Code{errcode.ForwardingDenied, errcode.DNSUnreachable} {
		if e.warnings[string(c)] {
			ws = append(ws, string(c))
		}
	}
	return ws
}

func (e *Engine) setWarning(c errcode.Code, on bool) {
	e.mu.Lock()
	if e.warnings[string(c)] == on {
		e.mu.Unlock()
		return
	}
	e.warnings[string(c)] = on
	s := e.status
	if s.State != On {
		e.mu.Unlock()
		return
	}
	s.Warnings = e.warningList()
	e.status = s
	e.notify.push(s)
	e.mu.Unlock()
}

func (e *Engine) onFlow(ev netstack.FlowEvent) {
	if ev.Event == netstack.FlowFail && ev.Reason == string(errcode.ForwardingDenied) {
		e.log(LevelWarn, CompTunnel, "server refused to forward to "+ev.Dst)
		e.setWarning(errcode.ForwardingDenied, true)
	}
	if e.cb.OnFlow != nil {
		e.cb.OnFlow(ev)
	}
}

// dialTCP is the netstack's and the DNS pool's way out. While there is no
// SSH connection it fails at once, so apps get a RST instead of hanging.
func (e *Engine) dialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	c := e.client.Load()
	if c == nil {
		return nil, errcode.New(errcode.TunnelDown, errors.New("tunnel is reconnecting"))
	}
	return c.DialTCP(ctx, dst)
}

// enter starts a phase whose context can be interrupted by NetworkChanged,
// RetryNow, keepalive failure, or connection loss.
func (e *Engine) enter(p phase) context.Context {
	ctx, cancel := context.WithCancelCause(e.ctx)
	e.mu.Lock()
	e.phase, e.phaseCancel = p, cancel
	e.mu.Unlock()
	return ctx
}

func (e *Engine) leave(ctx context.Context) error {
	e.mu.Lock()
	cancel := e.phaseCancel
	e.phase, e.phaseCancel = phaseIdle, nil
	e.mu.Unlock()
	cause := context.Cause(ctx)
	if cancel != nil {
		cancel(nil)
	}
	return cause
}

func (e *Engine) run() {
	defer close(e.done)
	defer e.teardown()

	attempt := 0
	reason := ""
	first := true
	for e.ctx.Err() == nil {
		// Dial.
		if first {
			e.setStatus(Status{State: Connecting, Step: string(sshx.StepResolving)})
		} else {
			e.setStatus(Status{State: Reconnecting, Reason: reason, Attempt: attempt, Code: e.Status().Code})
		}
		ctx := e.enter(phaseDial)
		opts := e.sshOpts
		isFirst := first
		opts.OnStep = func(s sshx.Step) {
			if isFirst {
				e.setStatus(Status{State: Connecting, Step: string(s)})
			}
			if s == sshx.StepAuth {
				e.log(LevelInfo, CompSSH, "host key verified")
			}
		}
		e.log(LevelInfo, CompSSH, fmt.Sprintf("connecting to %s@%s:%d", opts.User, opts.Host, opts.Port))
		c, err := sshx.Dial(ctx, opts)
		cause := e.leave(ctx)
		if e.ctx.Err() != nil {
			if c != nil {
				c.Close()
			}
			return
		}
		if errors.Is(cause, errNetworkChanged) {
			if c != nil {
				c.Close()
			}
			reason, attempt = ReasonNetworkChanged, 0
			first = false
			continue
		}
		if err != nil {
			code := errcode.Of(err)
			e.log(LevelError, CompSSH, err.Error())
			if errcode.Permanent(code) {
				if !e.attention(code, err) {
					return
				}
				first, attempt = true, 0
				continue
			}
			first = false
			attempt++
			reason = ReasonDialFailed
			e.mu.Lock()
			e.status.Code = string(code)
			e.mu.Unlock()
			ok, netChanged := e.backoffWait(attempt, reason, code)
			if !ok {
				return
			}
			if netChanged {
				reason, attempt = ReasonNetworkChanged, 0
			}
			continue
		}

		// Connected.
		e.log(LevelInfo, CompSSH, "authenticated; server "+string(c.ServerVersion()))
		// Warnings describe this connection; start clean.
		e.mu.Lock()
		clear(e.warnings)
		e.mu.Unlock()
		e.dns.Reset()
		e.client.Store(c)
		connectedAt := e.clock.Now()
		reason = e.online(c, isFirst)
		e.client.Store(nil)
		c.Close()
		e.mu.Lock()
		ns := e.ns
		e.mu.Unlock()
		if ns != nil {
			ns.CloseFlows()
		}
		e.dns.Reset()
		if e.ctx.Err() != nil {
			return
		}
		e.log(LevelWarn, CompSSH, "connection lost: "+reason)
		first = false
		if e.clock.Now().Sub(connectedAt) >= stableAfter {
			attempt = 0
		}
		if reason == ReasonNetworkChanged {
			attempt = 0
			continue
		}
		attempt++
		ok, netChanged := e.backoffWait(attempt, reason, "")
		if !ok {
			return
		}
		if netChanged {
			reason, attempt = ReasonNetworkChanged, 0
		}
	}
}

// online runs while c is connected: it waits for the link if needed, then
// reports On until the connection dies. It returns the reconnect reason.
func (e *Engine) online(c *sshx.Client, first bool) string {
	ctx := e.enter(phaseSSHReady)
	go func() {
		err := c.Wait()
		e.cancelPhase(ctx, fmt.Errorf("%w: %v", errConnClosed, err))
	}()
	go func() {
		if err := c.Keepalive(ctx, e.keepalive); err != nil {
			e.cancelPhase(ctx, err)
		}
	}()

	select {
	case <-e.linkReady:
	default:
		e.setStatus(Status{State: SSHReady})
		select {
		case <-e.linkReady:
		case <-ctx.Done():
		}
	}
	if ctx.Err() == nil {
		if first {
			e.setStatus(Status{State: Connecting, Step: StepTunnel})
		}
		e.mu.Lock()
		e.phase = phaseOn
		e.mu.Unlock()
		e.setStatus(Status{State: On})
		e.log(LevelInfo, CompTunnel, "tunnel up")
		<-ctx.Done()
	}
	cause := e.leave(ctx)
	switch {
	case errors.Is(cause, errNetworkChanged):
		return ReasonNetworkChanged
	case errors.Is(cause, sshx.ErrKeepaliveTimeout):
		return ReasonKeepalive
	default:
		return ReasonClosed
	}
}

// cancelPhase cancels ctx's phase if it is still the current one.
func (e *Engine) cancelPhase(ctx context.Context, cause error) {
	e.mu.Lock()
	cancel := e.phaseCancel
	e.mu.Unlock()
	if cancel != nil && ctx.Err() == nil {
		cancel(cause)
	}
}

// backoffWait reports Reconnecting with a countdown and waits. It returns
// false if the engine was stopped, and whether NetworkChanged cut it short.
func (e *Engine) backoffWait(attempt int, reason string, code errcode.Code) (ok, networkChanged bool) {
	d := backoff(attempt, e.rand())
	at := e.clock.Now().Add(d)
	e.setStatus(Status{State: Reconnecting, Reason: reason, Attempt: attempt, Code: string(code),
		NextRetryAt: at.UnixMilli()})
	ctx := e.enter(phaseBackoff)
	t := e.clock.NewTimer(d)
	select {
	case <-t.C():
	case <-ctx.Done():
		t.Stop()
	}
	cause := e.leave(ctx)
	return e.ctx.Err() == nil, errors.Is(cause, errNetworkChanged)
}

// attention reports NeedsAttention and waits for RetryNow. It returns false
// if the engine was stopped.
func (e *Engine) attention(code errcode.Code, err error) bool {
	detail := err.Error()
	var ce *errcode.Error
	if errors.As(err, &ce) && ce.Err != nil {
		detail = ce.Err.Error() // the code is already in Code
	}
	st := Status{State: NeedsAttention, Code: string(code), Detail: detail}
	var hke *sshx.HostKeyError
	if errors.As(err, &hke) {
		st.HostKey = &hke.Received
	}
	e.setStatus(st)
	ctx := e.enter(phaseAttention)
	<-ctx.Done()
	e.leave(ctx)
	return e.ctx.Err() == nil
}

func (e *Engine) teardown() {
	e.setStatus(Status{State: Disconnecting})
	if c := e.client.Swap(nil); c != nil {
		c.Close()
	}
	e.dns.Close()
	e.mu.Lock()
	ns := e.ns
	e.ns = nil
	e.mu.Unlock()
	if ns != nil {
		ns.Close() // closes the link and with it the TUN fd
	}
	e.setStatus(Status{State: Off})
}

// MTU returns the profile's TUN MTU.
func (e *Engine) MTU() int { return e.p.Tun.MTU }
