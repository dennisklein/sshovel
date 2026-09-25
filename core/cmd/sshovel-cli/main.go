// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Command sshovel-cli runs the sshovel engine on Linux with a TUN device, so
// the core can be exercised against test-env without Android. It is a debug
// tool: it needs CAP_NET_ADMIN and configures the interface with ip(8).
//
//	sshovel-cli -profile p.json -key ~/.ssh/id_ed25519 hostkey
//	sshovel-cli -profile p.json -key ~/.ssh/id_ed25519 routes
//	sshovel-cli -profile p.json -key ~/.ssh/id_ed25519 up
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/miekg/dns"
	"gvisor.dev/gvisor/pkg/tcpip/link/tun"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/dnsproxy"
	"github.com/dennisklein/sshovel/core/engine"
	"github.com/dennisklein/sshovel/core/netstack"
	"github.com/dennisklein/sshovel/core/sshx"
)

func main() {
	profilePath := flag.String("profile", "", "profile JSON (ARCHITECTURE §8); auth.kind must be \"imported\"")
	keyPath := flag.String("key", "", "unencrypted OpenSSH/PEM private key file")
	tunName := flag.String("tun", "sshovel0", "TUN interface name (up)")
	upstream := flag.String("upstream", "", "resolver for direct DNS, host:port (default: first nameserver in /etc/resolv.conf)")
	resolvectl := flag.Bool("resolvectl", false, "point systemd-resolved at the tunnel for the profile's suffixes (up)")
	verbose := flag.Bool("v", false, "print DNS and flow events (up)")
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: %s -profile p.json -key id_key [flags] hostkey|routes|up\n", os.Args[0])
		flag.PrintDefaults()
	}
	flag.Parse()
	if *profilePath == "" || flag.NArg() != 1 {
		flag.Usage()
		os.Exit(2)
	}
	p, err := loadProfile(*profilePath)
	if err != nil {
		fatal(err)
	}
	up, err := newUpstream(*upstream)
	if err != nil {
		fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	switch flag.Arg(0) {
	case "hostkey":
		o := sshx.OptionsFor(p, nil)
		o.Lookup = lookup(up)
		info, err := sshx.FetchHostKey(ctx, o)
		if err != nil {
			fatal(err)
		}
		printJSON(info)
		fmt.Fprintf(os.Stderr, "\nTo pin it, add to the profile:\n  \"hostKey\": {\"type\": %q, \"fingerprint\": %q}\n", info.Type, info.Fingerprint)
	case "routes":
		c, err := dial(ctx, p, *keyPath, up)
		if err != nil {
			fatal(err)
		}
		defer c.Close()
		routes, err := c.DiscoverRoutes(ctx)
		if err != nil {
			fatal(err)
		}
		printJSON(routes)
	case "up":
		if err := runUp(ctx, p, *keyPath, *tunName, up, *resolvectl, *verbose); err != nil {
			fatal(err)
		}
	default:
		flag.Usage()
		os.Exit(2)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "sshovel-cli:", err)
	os.Exit(1)
}

func printJSON(v any) {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func loadProfile(path string) (*config.Profile, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	p, err := config.Parse(b)
	if err != nil {
		return nil, err
	}
	issues := p.Validate()
	for _, i := range issues {
		fmt.Fprintf(os.Stderr, "profile %s: %s %s %s\n", i.Severity, i.Field, i.Code, i.Suggestion)
	}
	if config.HasErrors(issues) {
		return nil, errors.New("invalid profile")
	}
	return p, nil
}

func signer(p *config.Profile, keyPath string) (sshx.SignDigestFunc, []byte, error) {
	if p.Auth.Kind != config.AuthImported {
		return nil, nil, errors.New(`the CLI has no Keystore: set auth.kind to "imported" and pass -key`)
	}
	if keyPath == "" {
		return nil, nil, errors.New("-key is required")
	}
	key, err := os.ReadFile(keyPath)
	return nil, key, err
}

func dial(ctx context.Context, p *config.Profile, keyPath string, up dnsproxy.UpstreamFunc) (*sshx.Client, error) {
	sign, key, err := signer(p, keyPath)
	if err != nil {
		return nil, err
	}
	s, err := sshx.SignerFor(p, sign, key)
	if err != nil {
		return nil, err
	}
	o := sshx.OptionsFor(p, s)
	o.Lookup = lookup(up)
	return sshx.Dial(ctx, o)
}

func lookup(up dnsproxy.UpstreamFunc) sshx.LookupFunc {
	return func(ctx context.Context, host string) ([]netip.Addr, error) {
		return dnsproxy.LookupIPv4(ctx, up, host)
	}
}

func runUp(ctx context.Context, p *config.Profile, keyPath, name string, up dnsproxy.UpstreamFunc, resolved, verbose bool) error {
	sign, key, err := signer(p, keyPath)
	if err != nil {
		return err
	}
	s, err := sshx.SignerFor(p, sign, key)
	if err != nil {
		return err
	}
	if a, err := netip.ParseAddr(p.Server.Host); err == nil {
		for _, r := range p.RoutePrefixes() {
			if r.Contains(a) {
				fmt.Fprintf(os.Stderr, "warning: server %s is inside routed %s; the CLI can't protect its socket, so traffic would loop\n", a, r)
			}
		}
	}

	fd, err := tun.Open(name)
	if err != nil {
		return fmt.Errorf("open TUN %s (needs CAP_NET_ADMIN): %w", name, err)
	}
	link, err := netstack.NewTUNLink(fd, uint32(p.Tun.MTU))
	if err != nil {
		return err
	}
	tunPrefix := p.TunPrefix()
	cmds := [][]string{
		{"ip", "addr", "add", netip.PrefixFrom(p.TunAddr(), tunPrefix.Bits()).String(), "dev", name},
		{"ip", "link", "set", "dev", name, "mtu", fmt.Sprint(p.Tun.MTU), "up"},
	}
	for _, r := range p.RoutePrefixes() {
		cmds = append(cmds, []string{"ip", "route", "add", r.String(), "dev", name})
	}
	if resolved {
		cmds = append(cmds, []string{"resolvectl", "dns", name, p.Tun.DNSVirtualIP})
		var doms []string
		for _, s := range p.DNS.Suffixes {
			doms = append(doms, "~"+strings.TrimSuffix(s, "."))
		}
		if len(doms) > 0 {
			cmds = append(cmds, append([]string{"resolvectl", "domain", name}, doms...))
		}
	}

	e := engine.New(engine.Params{
		Profile: p,
		Signer:  s,
		Callbacks: engine.Callbacks{
			Upstream: up,
			OnState: func(st engine.Status) {
				b, _ := json.Marshal(st)
				fmt.Printf("%s state %s\n", ts(), b)
			},
			Log: func(level int, comp, msg string) {
				fmt.Printf("%s %s %-6s %s\n", ts(), [...]string{"D", "I", "W", "E"}[level], comp, msg)
			},
			OnDNSEvent: func(ev dnsproxy.Event) {
				if verbose {
					b, _ := json.Marshal(ev)
					fmt.Printf("%s dns %s\n", ts(), b)
				}
			},
			OnFlow: func(ev netstack.FlowEvent) {
				if verbose || ev.Event == netstack.FlowFail {
					b, _ := json.Marshal(ev)
					fmt.Printf("%s flow %s\n", ts(), b)
				}
			},
		},
	})
	if err := e.Start(link); err != nil {
		return err
	}
	defer e.Stop()
	for _, c := range cmds {
		if out, err := exec.Command(c[0], c[1:]...).CombinedOutput(); err != nil {
			return fmt.Errorf("%s: %v: %s", strings.Join(c, " "), err, out)
		}
	}
	fmt.Fprintf(os.Stderr, "%s up. DNS server %s. Stop with Ctrl-C; SIGUSR1 = network changed, SIGUSR2 = retry now.\n",
		name, p.Tun.DNSVirtualIP)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGUSR1, syscall.SIGUSR2)
	defer signal.Stop(sig)
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case s := <-sig:
			if s == syscall.SIGUSR1 {
				e.NetworkChanged()
			} else {
				e.RetryNow()
			}
		case <-t.C:
			b, _ := json.Marshal(e.Stats())
			fmt.Printf("%s stats %s\n", ts(), b)
		}
	}
}

func ts() string { return time.Now().Format("15:04:05.000") }

// newUpstream returns a plain resolver client standing in for Android's
// DnsResolver.rawQuery: UDP, retried over TCP when truncated.
func newUpstream(addr string) (dnsproxy.UpstreamFunc, error) {
	if addr == "" {
		var err error
		if addr, err = resolvConf(); err != nil {
			return nil, err
		}
	}
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(addr, "53")
	}
	return func(ctx context.Context, q []byte) ([]byte, error) {
		m := new(dns.Msg)
		if err := m.Unpack(q); err != nil {
			return nil, err
		}
		c := &dns.Client{Net: "udp"}
		r, _, err := c.ExchangeContext(ctx, m, addr)
		if err == nil && r.Truncated {
			c.Net = "tcp"
			r, _, err = c.ExchangeContext(ctx, m, addr)
		}
		if err != nil {
			return nil, err
		}
		return r.Pack()
	}, nil
}

func resolvConf() (string, error) {
	f, err := os.Open("/etc/resolv.conf")
	if err != nil {
		return "", err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if fs := strings.Fields(sc.Text()); len(fs) >= 2 && fs[0] == "nameserver" {
			return fs[1], nil
		}
	}
	return "", errors.New("no nameserver in /etc/resolv.conf; use -upstream")
}
