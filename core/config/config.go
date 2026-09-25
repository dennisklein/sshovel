// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package config holds the profile schema shared with the Kotlin app
// (ARCHITECTURE §8) and its validation rules (ARCHITECTURE §3).
package config

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"time"
)

// Profile is one connection profile. JSON field names match the Kotlin
// @Serializable data classes.
type Profile struct {
	ID                string   `json:"id"`
	Name              string   `json:"name"`
	Server            Server   `json:"server"`
	Auth              Auth     `json:"auth"`
	HostKey           *HostKey `json:"hostKey,omitempty"`
	Routes            []string `json:"routes"`
	ExcludedRoutes    []string `json:"excludedRoutes"`
	DNS               DNS      `json:"dns"`
	Apps              Apps     `json:"apps"`
	Tun               Tun      `json:"tun"`
	KeepaliveSec      int      `json:"keepaliveSec"`
	ConnectTimeoutSec int      `json:"connectTimeoutSec"`
}

type Server struct {
	Host string `json:"host"`
	Port int    `json:"port"`
	User string `json:"user"`
}

// Auth kinds.
const (
	AuthKeystore = "keystore"
	AuthImported = "imported"
)

type Auth struct {
	Kind string `json:"kind"`
	// Alias names the Keystore key, or the vault entry for imported keys.
	Alias string `json:"alias"`
	// PublicKeyPkix is the base64 PKIX (SubjectPublicKeyInfo) public key of a
	// Keystore key. encoding/json decodes base64 into []byte.
	PublicKeyPkix []byte `json:"publicKeyPkix,omitempty"`
}

// HostKey is the pinned server identity.
type HostKey struct {
	Type        string `json:"type"`
	Fingerprint string `json:"fingerprint"`
	PinnedAt    string `json:"pinnedAt,omitempty"`
}

type DNS struct {
	Server         string   `json:"server"`
	Suffixes       []string `json:"suffixes"`
	SearchDomains  []string `json:"searchDomains"`
	ReverseLookups bool     `json:"reverseLookups"`
	HideAAAA       bool     `json:"hideAAAA"`
}

// App modes.
const (
	AppsAll     = "all"
	AppsInclude = "include"
	AppsExclude = "exclude"
)

type Apps struct {
	Mode     string   `json:"mode"`
	Packages []string `json:"packages"`
}

type Tun struct {
	CIDR         string `json:"cidr"`
	DNSVirtualIP string `json:"dnsVirtualIp"`
	MTU          int    `json:"mtu"`
}

// Defaults (ARCHITECTURE §3, §6, §8).
const (
	DefaultPort              = 22
	DefaultTunCIDR           = "198.18.0.0/24"
	DefaultDNSVirtualIP      = "198.18.0.53"
	DefaultMTU               = 1500
	DefaultKeepaliveSec      = 20
	DefaultConnectTimeoutSec = 10
)

// Parse decodes profile JSON and fills in defaults. It does not validate;
// call Validate for that.
func Parse(data []byte) (*Profile, error) {
	var p Profile
	if err := json.Unmarshal(data, &p); err != nil {
		return nil, fmt.Errorf("profile JSON: %w", err)
	}
	p.applyDefaults()
	return &p, nil
}

func (p *Profile) applyDefaults() {
	if p.Server.Port == 0 {
		p.Server.Port = DefaultPort
	}
	if p.Apps.Mode == "" {
		p.Apps.Mode = AppsAll
	}
	if p.Tun.CIDR == "" {
		p.Tun.CIDR = DefaultTunCIDR
	}
	if p.Tun.DNSVirtualIP == "" {
		p.Tun.DNSVirtualIP = DefaultDNSVirtualIP
	}
	if p.Tun.MTU == 0 {
		p.Tun.MTU = DefaultMTU
	}
	if p.KeepaliveSec == 0 {
		p.KeepaliveSec = DefaultKeepaliveSec
	}
	if p.ConnectTimeoutSec == 0 {
		p.ConnectTimeoutSec = DefaultConnectTimeoutSec
	}
}

// The accessors below assume a profile that passed Validate without errors.

// RoutePrefixes returns the parsed routes.
func (p *Profile) RoutePrefixes() []netip.Prefix { return mustPrefixes(p.Routes) }

// TunPrefix returns the TUN subnet; TunAddr is its first host address.
func (p *Profile) TunPrefix() netip.Prefix { return netip.MustParsePrefix(p.Tun.CIDR) }

// TunAddr is the address assigned to the TUN interface (first host of the
// tun subnet, e.g. 198.18.0.1).
func (p *Profile) TunAddr() netip.Addr { return p.TunPrefix().Addr().Next() }

func (p *Profile) DNSVirtualIP() netip.Addr { return netip.MustParseAddr(p.Tun.DNSVirtualIP) }

// DNSServer returns the intranet resolver, or the zero Addr if none is set.
func (p *Profile) DNSServer() netip.Addr {
	if p.DNS.Server == "" {
		return netip.Addr{}
	}
	return netip.MustParseAddr(p.DNS.Server)
}

func (p *Profile) Keepalive() time.Duration { return time.Duration(p.KeepaliveSec) * time.Second }

func (p *Profile) ConnectTimeout() time.Duration {
	return time.Duration(p.ConnectTimeoutSec) * time.Second
}

func mustPrefixes(ss []string) []netip.Prefix {
	out := make([]netip.Prefix, 0, len(ss))
	for _, s := range ss {
		out = append(out, netip.MustParsePrefix(s))
	}
	return out
}
