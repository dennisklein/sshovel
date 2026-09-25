// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package config

import (
	"fmt"
	"net/netip"
	"strings"
)

// Issue codes returned by Validate. The Kotlin form maps them to inline copy.
const (
	CodeRequired         = "REQUIRED"
	CodeInvalidHost      = "INVALID_HOST"
	CodeInvalidPort      = "INVALID_PORT"
	CodeInvalidCIDR      = "INVALID_CIDR"
	CodeNotCanonical     = "NOT_CANONICAL" // host bits set; Suggestion holds the canonical form
	CodeNotIPv4          = "NOT_IPV4"      // v1 tunnels IPv4 only (ARCHITECTURE §10)
	CodeRouteOverlap     = "ROUTE_OVERLAP" // warning; Suggestion holds the merged prefix
	CodeDuplicate        = "DUPLICATE"     // same route listed twice
	CodeTunOverlapsRoute = "TUN_OVERLAPS_ROUTE"
	CodeDNSVIPOutsideTun = "DNS_VIP_OUTSIDE_TUN"
	CodeDNSVIPReserved   = "DNS_VIP_RESERVED" // network, broadcast, or the TUN's own address
	CodeInvalidIP        = "INVALID_IP"
	CodeDNSServerMissing = "DNS_SERVER_MISSING" // suffixes set without a server
	CodeInvalidDomain    = "INVALID_DOMAIN"
	CodeInvalidMTU       = "INVALID_MTU"
	CodeOutOfRange       = "OUT_OF_RANGE"
	CodeInvalidAuthKind  = "INVALID_AUTH_KIND"
	CodeInvalidPublicKey = "INVALID_PUBLIC_KEY"
	CodeInvalidAppsMode  = "INVALID_APPS_MODE"
	CodeNoApps           = "NO_APPS" // include/exclude mode with an empty list
	CodeTunTooSmall      = "TUN_TOO_SMALL"
)

// Severities.
const (
	SeverityError   = "error"
	SeverityWarning = "warning"
)

// Issue is one validation finding. Field uses JSON paths, e.g. "routes[1]".
type Issue struct {
	Field      string `json:"field"`
	Code       string `json:"code"`
	Severity   string `json:"severity"`
	Suggestion string `json:"suggestion,omitempty"`
}

// HasErrors reports whether any issue is an error (warnings don't block).
func HasErrors(issues []Issue) bool {
	for _, i := range issues {
		if i.Severity == SeverityError {
			return true
		}
	}
	return false
}

// Validate checks the profile against ARCHITECTURE §3 rules. It returns
// errors and warnings in field order; nil means the profile is fine.
func (p *Profile) Validate() []Issue {
	v := &validator{}

	// Server
	if strings.TrimSpace(p.Server.Host) == "" {
		v.err("server.host", CodeRequired)
	} else if !validHost(p.Server.Host) {
		v.err("server.host", CodeInvalidHost)
	}
	if p.Server.Port < 1 || p.Server.Port > 65535 {
		v.err("server.port", CodeInvalidPort)
	}
	if strings.TrimSpace(p.Server.User) == "" {
		v.err("server.user", CodeRequired)
	}

	// Auth
	switch p.Auth.Kind {
	case AuthKeystore:
		if p.Auth.Alias == "" {
			v.err("auth.alias", CodeRequired)
		}
		if len(p.Auth.PublicKeyPkix) == 0 {
			v.err("auth.publicKeyPkix", CodeRequired)
		} else if _, err := ParsePKIX(p.Auth.PublicKeyPkix); err != nil {
			v.err("auth.publicKeyPkix", CodeInvalidPublicKey)
		}
	case AuthImported:
		if p.Auth.Alias == "" {
			v.err("auth.alias", CodeRequired)
		}
	case "":
		v.err("auth.kind", CodeRequired)
	default:
		v.err("auth.kind", CodeInvalidAuthKind)
	}

	// Tun
	tun, tunOK := v.prefix("tun.cidr", p.Tun.CIDR)
	if tunOK && tun.Bits() > 30 {
		v.err("tun.cidr", CodeTunTooSmall)
		tunOK = false
	}
	vip, vipErr := netip.ParseAddr(p.Tun.DNSVirtualIP)
	switch {
	case vipErr != nil:
		v.err("tun.dnsVirtualIp", CodeInvalidIP)
	case !vip.Is4():
		v.err("tun.dnsVirtualIp", CodeNotIPv4)
	case tunOK && !tun.Contains(vip):
		v.err("tun.dnsVirtualIp", CodeDNSVIPOutsideTun)
	case tunOK && (vip == tun.Addr() || vip == lastAddr(tun) || vip == tun.Addr().Next()):
		v.err("tun.dnsVirtualIp", CodeDNSVIPReserved)
	}
	if p.Tun.MTU < 1280 || p.Tun.MTU > 9000 {
		v.err("tun.mtu", CodeInvalidMTU)
	}

	// Routes
	if len(p.Routes) == 0 {
		v.err("routes", CodeRequired)
	}
	var routes []netip.Prefix
	for i, s := range p.Routes {
		f := fmt.Sprintf("routes[%d]", i)
		r, ok := v.prefix(f, s)
		if !ok {
			continue
		}
		if tunOK && r.Overlaps(tun) {
			v.err(f, CodeTunOverlapsRoute)
		}
		dup := false
		for _, o := range routes {
			if o == r {
				v.warn(f, CodeDuplicate, "")
				dup = true
				break
			}
			if o.Overlaps(r) {
				v.warn(f, CodeRouteOverlap, merge(o, r).String())
			}
		}
		if !dup {
			routes = append(routes, r)
		}
	}
	for i, s := range p.ExcludedRoutes {
		v.prefix(fmt.Sprintf("excludedRoutes[%d]", i), s)
	}

	// DNS
	if p.DNS.Server != "" {
		a, err := netip.ParseAddr(p.DNS.Server)
		switch {
		case err != nil:
			v.err("dns.server", CodeInvalidIP)
		case !a.Is4():
			v.err("dns.server", CodeNotIPv4)
		}
	} else if len(p.DNS.Suffixes) > 0 {
		v.err("dns.server", CodeDNSServerMissing)
	}
	for i, s := range p.DNS.Suffixes {
		if !validDomain(s) {
			v.err(fmt.Sprintf("dns.suffixes[%d]", i), CodeInvalidDomain)
		}
	}
	for i, s := range p.DNS.SearchDomains {
		if !validDomain(s) {
			v.err(fmt.Sprintf("dns.searchDomains[%d]", i), CodeInvalidDomain)
		}
	}

	// Apps
	switch p.Apps.Mode {
	case AppsAll:
	case AppsInclude, AppsExclude:
		if len(p.Apps.Packages) == 0 {
			v.err("apps.packages", CodeNoApps)
		}
	default:
		v.err("apps.mode", CodeInvalidAppsMode)
	}

	// Timers
	if p.KeepaliveSec < 5 || p.KeepaliveSec > 300 {
		v.err("keepaliveSec", CodeOutOfRange)
	}
	if p.ConnectTimeoutSec < 1 || p.ConnectTimeoutSec > 120 {
		v.err("connectTimeoutSec", CodeOutOfRange)
	}
	return v.issues
}

type validator struct{ issues []Issue }

func (v *validator) err(field, code string) {
	v.issues = append(v.issues, Issue{Field: field, Code: code, Severity: SeverityError})
}

func (v *validator) warn(field, code, suggestion string) {
	v.issues = append(v.issues, Issue{Field: field, Code: code, Severity: SeverityWarning, Suggestion: suggestion})
}

// prefix parses an IPv4 CIDR that must be canonical.
func (v *validator) prefix(field, s string) (netip.Prefix, bool) {
	p, err := netip.ParsePrefix(strings.TrimSpace(s))
	if err != nil {
		v.err(field, CodeInvalidCIDR)
		return netip.Prefix{}, false
	}
	if !p.Addr().Is4() {
		v.err(field, CodeNotIPv4)
		return netip.Prefix{}, false
	}
	if m := p.Masked(); m != p || s != p.String() {
		v.issues = append(v.issues, Issue{Field: field, Code: CodeNotCanonical, Severity: SeverityError, Suggestion: m.String()})
		return netip.Prefix{}, false
	}
	return p, true
}

// merge returns the smallest prefix containing both a and b. For overlapping
// prefixes that is simply the shorter one.
func merge(a, b netip.Prefix) netip.Prefix {
	if a.Bits() <= b.Bits() {
		return a
	}
	return b
}

func lastAddr(p netip.Prefix) netip.Addr {
	a := p.Addr().As4()
	host := uint32(1)<<(32-p.Bits()) - 1
	n := (uint32(a[0])<<24 | uint32(a[1])<<16 | uint32(a[2])<<8 | uint32(a[3])) | host
	return netip.AddrFrom4([4]byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n)})
}

func validHost(h string) bool {
	if _, err := netip.ParseAddr(h); err == nil {
		return true
	}
	return validDomain(h)
}

// validDomain accepts a DNS name with an optional trailing dot. Single-label
// names ("internal") are allowed.
func validDomain(s string) bool {
	s = strings.TrimSuffix(s, ".")
	if s == "" || len(s) > 253 {
		return false
	}
	for _, label := range strings.Split(s, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
				return false
			}
		}
	}
	return true
}
