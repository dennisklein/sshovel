// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package config

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"testing"
)

func pkixB64(t *testing.T) string {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(der)
}

// exampleJSON is the ARCHITECTURE §8 example.
func exampleJSON(t *testing.T) string {
	return fmt.Sprintf(`{
  "id": "uuid",
  "name": "Office",
  "server": { "host": "jump.example.com", "port": 22, "user": "alice" },
  "auth": { "kind": "keystore", "alias": "sshovel-key-1", "publicKeyPkix": %q },
  "hostKey": { "type": "ecdsa-sha2-nistp256", "fingerprint": "SHA256:abc", "pinnedAt": "2026-09-23T10:00:00Z" },
  "routes": ["10.0.0.0/8", "172.16.0.0/12"],
  "excludedRoutes": [],
  "dns": {
    "server": "10.1.0.53",
    "suffixes": ["corp.example", "internal"],
    "searchDomains": ["corp.example"],
    "reverseLookups": true,
    "hideAAAA": true
  },
  "apps": { "mode": "all", "packages": [] },
  "tun": { "cidr": "198.18.0.0/24", "dnsVirtualIp": "198.18.0.53", "mtu": 1500 },
  "keepaliveSec": 20,
  "connectTimeoutSec": 10
}`, pkixB64(t))
}

func TestParseExample(t *testing.T) {
	p, err := Parse([]byte(exampleJSON(t)))
	if err != nil {
		t.Fatal(err)
	}
	if issues := p.Validate(); issues != nil {
		t.Fatalf("example profile has issues: %+v", issues)
	}
	if got := p.TunAddr().String(); got != "198.18.0.1" {
		t.Errorf("TunAddr = %s", got)
	}
	if p.HostKey.Fingerprint != "SHA256:abc" || len(p.RoutePrefixes()) != 2 {
		t.Errorf("unexpected parse result %+v", p)
	}
}

func TestDefaults(t *testing.T) {
	p, err := Parse([]byte(`{"server":{"host":"h","user":"u"},"auth":{"kind":"imported","alias":"a"},"routes":["10.0.0.0/8"]}`))
	if err != nil {
		t.Fatal(err)
	}
	if p.Server.Port != 22 || p.Tun.CIDR != DefaultTunCIDR || p.Tun.DNSVirtualIP != DefaultDNSVirtualIP ||
		p.Tun.MTU != 1500 || p.KeepaliveSec != 20 || p.ConnectTimeoutSec != 10 || p.Apps.Mode != AppsAll {
		t.Errorf("defaults not applied: %+v", p)
	}
	if issues := p.Validate(); issues != nil {
		t.Errorf("minimal profile has issues: %+v", issues)
	}
}

func TestValidate(t *testing.T) {
	type issue struct{ field, code, sev, sugg string }
	cases := []struct {
		name   string
		mutate func(*Profile)
		want   []issue
	}{
		{"non-canonical route", func(p *Profile) { p.Routes = []string{"10.1.2.3/8"} },
			[]issue{{"routes[0]", CodeNotCanonical, SeverityError, "10.0.0.0/8"}}},
		{"bad cidr", func(p *Profile) { p.Routes = []string{"10.0.0.0/33"} },
			[]issue{{"routes[0]", CodeInvalidCIDR, SeverityError, ""}}},
		{"ipv6 route", func(p *Profile) { p.Routes = []string{"fd00::/8"} },
			[]issue{{"routes[0]", CodeNotIPv4, SeverityError, ""}}},
		{"overlapping routes warn with merge", func(p *Profile) { p.Routes = []string{"10.0.0.0/8", "10.77.0.0/24"} },
			[]issue{{"routes[1]", CodeRouteOverlap, SeverityWarning, "10.0.0.0/8"}}},
		{"duplicate route", func(p *Profile) { p.Routes = []string{"10.0.0.0/8", "10.0.0.0/8"} },
			[]issue{{"routes[1]", CodeDuplicate, SeverityWarning, ""}}},
		{"tun overlaps route", func(p *Profile) {
			p.Routes = []string{"10.0.0.0/8"}
			p.Tun.CIDR = "10.99.0.0/24"
			p.Tun.DNSVirtualIP = "10.99.0.53"
		},
			[]issue{{"routes[0]", CodeTunOverlapsRoute, SeverityError, ""}}},
		{"vip outside tun", func(p *Profile) { p.Tun.DNSVirtualIP = "198.19.0.53" },
			[]issue{{"tun.dnsVirtualIp", CodeDNSVIPOutsideTun, SeverityError, ""}}},
		{"vip is tun address", func(p *Profile) { p.Tun.DNSVirtualIP = "198.18.0.1" },
			[]issue{{"tun.dnsVirtualIp", CodeDNSVIPReserved, SeverityError, ""}}},
		{"vip is broadcast", func(p *Profile) { p.Tun.DNSVirtualIP = "198.18.0.255" },
			[]issue{{"tun.dnsVirtualIp", CodeDNSVIPReserved, SeverityError, ""}}},
		{"suffixes without server", func(p *Profile) { p.DNS.Server = "" },
			[]issue{{"dns.server", CodeDNSServerMissing, SeverityError, ""}}},
		{"bad suffix", func(p *Profile) { p.DNS.Suffixes = []string{"corp..example"} },
			[]issue{{"dns.suffixes[0]", CodeInvalidDomain, SeverityError, ""}}},
		{"no routes", func(p *Profile) { p.Routes = nil },
			[]issue{{"routes", CodeRequired, SeverityError, ""}}},
		{"missing host and user", func(p *Profile) { p.Server.Host = ""; p.Server.User = "" },
			[]issue{{"server.host", CodeRequired, SeverityError, ""}, {"server.user", CodeRequired, SeverityError, ""}}},
		{"bad port", func(p *Profile) { p.Server.Port = 70000 },
			[]issue{{"server.port", CodeInvalidPort, SeverityError, ""}}},
		{"include without apps", func(p *Profile) { p.Apps.Mode = AppsInclude },
			[]issue{{"apps.packages", CodeNoApps, SeverityError, ""}}},
		{"bad key", func(p *Profile) { p.Auth.PublicKeyPkix = []byte("nope") },
			[]issue{{"auth.publicKeyPkix", CodeInvalidPublicKey, SeverityError, ""}}},
		{"mtu", func(p *Profile) { p.Tun.MTU = 500 },
			[]issue{{"tun.mtu", CodeInvalidMTU, SeverityError, ""}}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p, err := Parse([]byte(exampleJSON(t)))
			if err != nil {
				t.Fatal(err)
			}
			tc.mutate(p)
			var got []issue
			for _, i := range p.Validate() {
				got = append(got, issue{i.Field, i.Code, i.Severity, i.Suggestion})
			}
			if !slices.Equal(got, tc.want) {
				t.Errorf("got %+v\nwant %+v", got, tc.want)
			}
		})
	}
}

func TestHasErrors(t *testing.T) {
	if HasErrors([]Issue{{Severity: SeverityWarning}}) {
		t.Error("warnings must not count as errors")
	}
	if !HasErrors([]Issue{{Severity: SeverityWarning}, {Severity: SeverityError}}) {
		t.Error("missed error")
	}
}

func TestIssueJSON(t *testing.T) {
	b, _ := json.Marshal(Issue{Field: "routes[0]", Code: CodeNotCanonical, Severity: SeverityError, Suggestion: "10.0.0.0/8"})
	if !strings.Contains(string(b), `"field":"routes[0]"`) || !strings.Contains(string(b), `"code":"NOT_CANONICAL"`) {
		t.Errorf("unexpected JSON %s", b)
	}
}
