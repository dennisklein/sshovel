// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package mobile is the gomobile surface of the core (ARCHITECTURE §8). It
// only converts between gomobile-safe types and the core packages; business
// logic lives elsewhere.
//
// Errors returned to Kotlin have messages that start with an error code from
// ARCHITECTURE §8, followed by ": " and a detail, e.g.
// "HOST_KEY_MISMATCH: host key does not match the pinned key".
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/dnsproxy"
	"github.com/dennisklein/sshovel/core/engine"
	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/netstack"
	"github.com/dennisklein/sshovel/core/sshx"
)

// Platform is implemented in Kotlin (PlatformBridge). Calls may block the
// calling goroutine and may arrive concurrently.
type Platform interface {
	// Protect excludes a socket from the VPN (VpnService.protect).
	Protect(fd int32) bool
	// SignDigest signs a SHA-256 digest with a Keystore key
	// (NONEwithECDSA) and returns the ASN.1 DER signature.
	SignDigest(keyAlias string, digest []byte) ([]byte, error)
	// QueryUpstreamDNS resolves a raw DNS query on the underlying network
	// (DnsResolver.rawQuery) and returns the raw answer.
	QueryUpstreamDNS(query []byte) ([]byte, error)
	// OnState receives engine status JSON, see engine.Status.
	OnState(stateJSON string)
	// Log receives diagnostics lines: level 0 debug … 3 error; component is
	// SSH, DNS, or Tunnel.
	Log(level int32, component string, message string)
	// OnDnsEvent receives one dnsproxy.Event JSON per answered query.
	OnDnsEvent(eventJSON string)
	// OnFlowEvent receives netstack.FlowEvent JSON (open, close, fail).
	OnFlowEvent(eventJSON string)
}

// version is set with -ldflags "-X .../mobile.version=v1.2.3".
var version = "dev"

// Version returns the core version and the Go toolchain it was built with.
func Version() string { return version + " (" + runtime.Version() + ")" }

// Engine runs one tunnel. Create a new Engine for every connection.
type Engine struct {
	p  Platform
	mu sync.Mutex
	e  *engine.Engine
}

// NewEngine creates an idle engine.
func NewEngine(platform Platform) *Engine { return &Engine{p: platform} }

// Start connects in the background; progress arrives via OnState. With
// tunFd = -1 the engine authenticates first and reports "sshReady", after
// which Kotlin establishes the TUN and calls AttachTun (normal ordering).
// With a TUN fd (Always-on lockdown) it reports "on" once SSH is up. Go owns
// tunFd from here on and closes it on Stop. importedKey is zeroed.
func (m *Engine) Start(tunFd int32, configJSON string, importedKey []byte) error {
	defer clear(importedKey)
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.e != nil {
		closeFd(tunFd)
		return errcode.New(errcode.Internal, errors.New("engine already started"))
	}
	p, err := parseValid(configJSON)
	if err != nil {
		closeFd(tunFd)
		return err
	}
	signer, err := sshx.SignerFor(p, m.p.SignDigest, importedKey)
	if err != nil {
		closeFd(tunFd)
		return err
	}
	e := engine.New(engine.Params{
		Profile:   p,
		Signer:    signer,
		Callbacks: callbacks(m.p),
	})
	if tunFd >= 0 {
		link, err := netstack.NewTUNLink(int(tunFd), uint32(p.Tun.MTU))
		if err != nil {
			return errcode.New(errcode.Internal, err)
		}
		if err := e.Start(link); err != nil {
			return errcode.New(errcode.Internal, err)
		}
	} else if err := e.Start(nil); err != nil {
		return errcode.New(errcode.Internal, err)
	}
	m.e = e
	return nil
}

// AttachTun hands over the TUN fd after "sshReady". Go owns it from here on.
func (m *Engine) AttachTun(tunFd int32) error {
	m.mu.Lock()
	e := m.e
	m.mu.Unlock()
	if e == nil {
		closeFd(tunFd)
		return errcode.New(errcode.Internal, errors.New("engine not started"))
	}
	link, err := netstack.NewTUNLink(int(tunFd), uint32(e.MTU()))
	if err != nil {
		return errcode.New(errcode.Internal, err)
	}
	if err := e.AttachLink(link); err != nil {
		return errcode.New(errcode.Internal, err)
	}
	return nil
}

// Stop disconnects, closes the TUN fd, and returns once the state is "off".
func (m *Engine) Stop() {
	m.mu.Lock()
	e := m.e
	m.mu.Unlock()
	if e != nil {
		e.Stop()
	}
}

// NetworkChanged tells the engine the underlying network changed: it
// reconnects at once instead of waiting for a backoff or a keepalive.
func (m *Engine) NetworkChanged() {
	if e := m.engine(); e != nil {
		e.NetworkChanged()
	}
}

// RetryNow skips the reconnect countdown, or retries after needsAttention.
func (m *Engine) RetryNow() {
	if e := m.engine(); e != nil {
		e.RetryNow()
	}
}

// StatsJSON returns the stats (ARCHITECTURE §7) as JSON.
func (m *Engine) StatsJSON() string {
	var s engine.Stats
	if e := m.engine(); e != nil {
		s = e.Stats()
	}
	return mustJSON(s)
}

func (m *Engine) engine() *engine.Engine {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.e
}

// FetchHostKey connects to the profile's server and returns its host key as
// {"type","fingerprint","line"} without authenticating.
func FetchHostKey(platform Platform, configJSON string) (string, error) {
	prof, err := parseValid(configJSON)
	if err != nil {
		return "", err
	}
	o := sshx.OptionsFor(prof, nil)
	o.Protect, o.Lookup = protectOf(platform), lookupOf(platform)
	info, err := sshx.FetchHostKey(context.Background(), o)
	if err != nil {
		return "", err
	}
	return mustJSON(info), nil
}

// DiscoverRoutes connects with the pinned host key and the profile's key and
// returns the server's IPv4 routes as [{"cidr","dev","isDefault",
// "isLinkLocal"}]. importedKey is zeroed.
func DiscoverRoutes(platform Platform, configJSON string, importedKey []byte) (string, error) {
	defer clear(importedKey)
	prof, err := parseValid(configJSON)
	if err != nil {
		return "", err
	}
	signer, err := sshx.SignerFor(prof, platform.SignDigest, importedKey)
	if err != nil {
		return "", err
	}
	o := sshx.OptionsFor(prof, signer)
	o.Protect, o.Lookup = protectOf(platform), lookupOf(platform)
	ctx, cancel := context.WithTimeout(context.Background(), prof.ConnectTimeout()+15*time.Second)
	defer cancel()
	c, err := sshx.Dial(ctx, o)
	if err != nil {
		return "", err
	}
	defer c.Close()
	routes, err := c.DiscoverRoutes(ctx)
	if err != nil {
		return "", err
	}
	return mustJSON(routes), nil
}

// AuthorizedKeyLine renders the recommended authorized_keys line for a
// Keystore public key (PKIX DER).
func AuthorizedKeyLine(pkixPublicKey []byte, comment string) (string, error) {
	return sshx.AuthorizedKeyLine(pkixPublicKey, comment)
}

// ValidateConfig returns "" for a valid profile, or a JSON list of
// {"field","code","severity","suggestion"}. Warnings alone (severity
// "warning") don't make a profile unusable.
func ValidateConfig(configJSON string) string {
	p, err := config.Parse([]byte(configJSON))
	if err != nil {
		return mustJSON([]config.Issue{{Field: "", Code: "INVALID_JSON", Severity: config.SeverityError}})
	}
	issues := p.Validate()
	if len(issues) == 0 {
		return ""
	}
	return mustJSON(issues)
}

func parseValid(configJSON string) (*config.Profile, error) {
	p, err := config.Parse([]byte(configJSON))
	if err != nil {
		return nil, errcode.New(errcode.Internal, err)
	}
	if issues := p.Validate(); config.HasErrors(issues) {
		var fs []string
		for _, i := range issues {
			if i.Severity == config.SeverityError {
				fs = append(fs, i.Field+"="+i.Code)
			}
		}
		return nil, errcode.New(errcode.Internal, fmt.Errorf("invalid profile: %s", strings.Join(fs, ", ")))
	}
	return p, nil
}

func callbacks(p Platform) engine.Callbacks {
	return engine.Callbacks{
		Protect:    protectOf(p),
		SignDigest: p.SignDigest,
		Upstream:   upstreamOf(p),
		OnState:    func(s engine.Status) { p.OnState(mustJSON(s)) },
		Log:        func(level int, comp, msg string) { p.Log(int32(level), comp, msg) },
		OnDNSEvent: func(e dnsproxy.Event) { p.OnDnsEvent(mustJSON(e)) },
		OnFlow:     func(e netstack.FlowEvent) { p.OnFlowEvent(mustJSON(e)) },
	}
}

func protectOf(p Platform) sshx.ProtectFunc {
	return func(fd int) bool { return p.Protect(int32(fd)) }
}

// upstreamOf bounds the blocking Kotlin call by ctx. rawQuery has its own
// timeout, so an abandoned call finishes on its own.
func upstreamOf(p Platform) dnsproxy.UpstreamFunc {
	return func(ctx context.Context, q []byte) ([]byte, error) {
		type result struct {
			b   []byte
			err error
		}
		ch := make(chan result, 1)
		go func() {
			b, err := p.QueryUpstreamDNS(q)
			ch <- result{b, err}
		}()
		select {
		case r := <-ch:
			return r.b, r.err
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

func lookupOf(p Platform) sshx.LookupFunc {
	up := upstreamOf(p)
	return func(ctx context.Context, host string) ([]netip.Addr, error) {
		return dnsproxy.LookupIPv4(ctx, up, host)
	}
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err) // only our own plain structs are marshaled
	}
	return string(b)
}
