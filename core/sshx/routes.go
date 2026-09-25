// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"sort"
	"strconv"
	"strings"

	"github.com/dennisklein/sshovel/core/errcode"
)

// Route is one IPv4 route discovered on the server.
type Route struct {
	CIDR        string `json:"cidr"`
	Dev         string `json:"dev"`
	IsDefault   bool   `json:"isDefault"`
	IsLinkLocal bool   `json:"isLinkLocal"`
}

// routeCommands are tried in order until one prints parseable routes.
// /proc/net/route covers minimal Linux servers without iproute2 or net-tools.
var routeCommands = []struct {
	cmd   string
	parse func([]byte) []Route
}{
	{"ip -4 route show", parseIPRoute},
	{"netstat -rn", parseNetstat},
	{"cat /proc/net/route", parseProcNetRoute},
}

// DiscoverRoutes runs route commands on the server. It returns
// ROUTE_DISCOVERY_UNAVAILABLE if the server refuses exec or no command
// produces routes.
func (c *Client) DiscoverRoutes(ctx context.Context) ([]Route, error) {
	var errs []error
	for _, rc := range routeCommands {
		out, err := c.run(ctx, rc.cmd)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", rc.cmd, err))
			if ctx.Err() != nil {
				break
			}
			continue
		}
		if routes := normalize(rc.parse(out)); len(routes) > 0 {
			return routes, nil
		}
		errs = append(errs, fmt.Errorf("%s: no routes in output", rc.cmd))
	}
	return nil, errcode.New(errcode.RouteDiscoveryUnavailable, errors.Join(errs...))
}

const maxRouteOutput = 1 << 20

func (c *Client) run(ctx context.Context, cmd string) ([]byte, error) {
	s, err := c.NewSession()
	if err != nil {
		return nil, err
	}
	defer s.Close()
	stop := context.AfterFunc(ctx, func() { s.Close() })
	defer stop()
	var out bytes.Buffer
	s.Stdout = &limitWriter{w: &out, n: maxRouteOutput}
	if err := s.Run(cmd); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

type limitWriter struct {
	w *bytes.Buffer
	n int
}

func (l *limitWriter) Write(p []byte) (int, error) {
	if l.w.Len()+len(p) > l.n {
		return 0, errors.New("output too large")
	}
	return l.w.Write(p)
}

var linkLocal = netip.MustParsePrefix("169.254.0.0/16")
var loopback = netip.MustParsePrefix("127.0.0.0/8")

// normalize canonicalizes, drops loopback and duplicates, and sorts.
func normalize(in []Route) []Route {
	seen := map[string]bool{}
	var out []Route
	for _, r := range in {
		p, err := netip.ParsePrefix(r.CIDR)
		if err != nil || !p.Addr().Is4() {
			continue
		}
		p = p.Masked()
		if loopback.Contains(p.Addr()) && p.Bits() >= loopback.Bits() {
			continue
		}
		key := p.String() + " " + r.Dev
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, Route{
			CIDR:        p.String(),
			Dev:         r.Dev,
			IsDefault:   p.Bits() == 0,
			IsLinkLocal: linkLocal.Contains(p.Addr()) && p.Bits() >= linkLocal.Bits(),
		})
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := netip.MustParsePrefix(out[i].CIDR), netip.MustParsePrefix(out[j].CIDR)
		if c := a.Addr().Compare(b.Addr()); c != 0 {
			return c < 0
		}
		return a.Bits() < b.Bits()
	})
	return out
}

func mk(p netip.Prefix, dev string) Route { return Route{CIDR: p.String(), Dev: dev} }

// parseIPRoute parses `ip -4 route show`:
//
//	default via 10.0.0.1 dev eth0 proto dhcp metric 100
//	10.77.0.0/24 dev eth1 proto kernel scope link src 10.77.0.2
//	192.0.2.7 via 10.0.0.1 dev eth0
//	unreachable 10.9.0.0/16
func parseIPRoute(out []byte) []Route {
	var rs []Route
	for _, f := range lines(out) {
		if len(f) == 0 {
			continue
		}
		switch f[0] {
		case "unreachable", "blackhole", "prohibit", "throw", "local", "broadcast", "anycast", "multicast", "nat":
			continue
		case "unicast":
			f = f[1:]
			if len(f) == 0 {
				continue
			}
		}
		var p netip.Prefix
		if f[0] == "default" {
			p = netip.MustParsePrefix("0.0.0.0/0")
		} else if pp, ok := parsePrefixOrHost(f[0]); ok {
			p = pp
		} else {
			continue
		}
		rs = append(rs, mk(p, fieldAfter(f, "dev")))
	}
	return rs
}

// parseNetstat parses Linux and BSD `netstat -rn` output. Linux:
//
//	Destination     Gateway         Genmask         Flags   MSS Window  irtt Iface
//	0.0.0.0         10.0.0.1        0.0.0.0         UG        0 0          0 eth0
//
// BSD/macOS (IPv4 section "Internet:" only):
//
//	Destination        Gateway            Flags     Netif Expire
//	default            192.168.1.1        UGS         em0
//	10.0.0/8           link#1             U           em0
func parseNetstat(out []byte) []Route {
	var rs []Route
	ls := lines(out)
	linux := false
	for _, f := range ls {
		if len(f) >= 3 && f[0] == "Destination" && f[2] == "Genmask" {
			linux = true
		}
	}
	if linux {
		for _, f := range ls {
			if len(f) < 8 {
				continue
			}
			dst, err1 := netip.ParseAddr(f[0])
			mask, err2 := netip.ParseAddr(f[2])
			if err1 != nil || err2 != nil || !dst.Is4() {
				continue
			}
			bits, ok := maskBits(mask.As4())
			if !ok {
				continue
			}
			if p, err := dst.Prefix(bits); err == nil {
				rs = append(rs, mk(p, f[len(f)-1]))
			}
		}
		return rs
	}
	inet := true
	for _, f := range ls {
		if len(f) == 1 && strings.HasSuffix(f[0], ":") {
			inet = f[0] == "Internet:"
			continue
		}
		if !inet || len(f) < 4 || f[0] == "Destination" {
			continue
		}
		var p netip.Prefix
		if f[0] == "default" {
			p = netip.MustParsePrefix("0.0.0.0/0")
		} else if pp, ok := parseBSDDest(f[0]); ok {
			p = pp
		} else {
			continue
		}
		rs = append(rs, mk(p, f[3]))
	}
	return rs
}

// parseBSDDest handles abbreviated BSD destinations: "10/8", "10.0.0/8",
// "192.168.1" (classful /24), "192.0.2.7" (host).
func parseBSDDest(s string) (netip.Prefix, bool) {
	addr, bitsStr, hasBits := strings.Cut(s, "/")
	parts := strings.Split(addr, ".")
	if len(parts) == 0 || len(parts) > 4 {
		return netip.Prefix{}, false
	}
	var b [4]byte
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 || n > 255 {
			return netip.Prefix{}, false
		}
		b[i] = byte(n)
	}
	bits := len(parts) * 8
	if hasBits {
		n, err := strconv.Atoi(bitsStr)
		if err != nil || n < 0 || n > 32 {
			return netip.Prefix{}, false
		}
		bits = n
	}
	return netip.PrefixFrom(netip.AddrFrom4(b), bits), true
}

// parseProcNetRoute parses /proc/net/route. Addresses are printed as the
// in-memory value of network-order bytes, so they appear reversed on
// little-endian servers (virtually all). Big-endian output is detected by a
// mask that is only contiguous when read as-is.
func parseProcNetRoute(out []byte) []Route {
	var rs []Route
	for _, f := range lines(out) {
		if len(f) < 8 || f[0] == "Iface" {
			continue
		}
		dst, err1 := hex.DecodeString(f[1])
		mask, err2 := hex.DecodeString(f[7])
		if err1 != nil || err2 != nil || len(dst) != 4 || len(mask) != 4 {
			continue
		}
		d := [4]byte{dst[3], dst[2], dst[1], dst[0]}
		bits, ok := maskBits([4]byte{mask[3], mask[2], mask[1], mask[0]})
		if !ok {
			if bits, ok = maskBits([4]byte(mask)); !ok {
				continue
			}
			d = [4]byte(dst)
		}
		if p, err := netip.AddrFrom4(d).Prefix(bits); err == nil {
			rs = append(rs, mk(p, f[0]))
		}
	}
	return rs
}

func maskBits(m [4]byte) (int, bool) {
	n := uint32(m[0])<<24 | uint32(m[1])<<16 | uint32(m[2])<<8 | uint32(m[3])
	bits := 0
	for n&0x80000000 != 0 {
		bits++
		n <<= 1
	}
	return bits, n == 0
}

func parsePrefixOrHost(s string) (netip.Prefix, bool) {
	if p, err := netip.ParsePrefix(s); err == nil {
		return p, true
	}
	if a, err := netip.ParseAddr(s); err == nil && a.Is4() {
		return netip.PrefixFrom(a, 32), true
	}
	return netip.Prefix{}, false
}

func fieldAfter(f []string, key string) string {
	for i := 0; i+1 < len(f); i++ {
		if f[i] == key {
			return f[i+1]
		}
	}
	return ""
}

func lines(out []byte) [][]string {
	var ls [][]string
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		ls = append(ls, strings.Fields(sc.Text()))
	}
	return ls
}
