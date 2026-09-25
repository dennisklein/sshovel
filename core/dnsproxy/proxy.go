// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package dnsproxy is the split-DNS resolver behind the VPN's DNS virtual IP
// (ARCHITECTURE §5).
package dnsproxy

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/miekg/dns"
)

// TunnelDialFunc opens a TCP connection through the SSH tunnel.
type TunnelDialFunc func(ctx context.Context, dst netip.AddrPort) (net.Conn, error)

// UpstreamFunc resolves a raw DNS query on the underlying (non-VPN) network;
// on Android this is DnsResolver.rawQuery.
type UpstreamFunc func(ctx context.Context, query []byte) ([]byte, error)

// Config configures the proxy.
type Config struct {
	Server         netip.Addr // intranet resolver; zero means no tunnel path
	Suffixes       []string
	ReverseLookups bool
	Routes         []netip.Prefix
	HideAAAA       bool
	QueryTimeout   time.Duration // default 4 s
	PoolSize       int           // default 2
}

// Routes an answer took.
const (
	RouteTunnel = "tunnel"
	RouteDirect = "direct"
)

// Event is one diagnostics record (ARCHITECTURE §5, Diagnostics).
type Event struct {
	Name                  string   `json:"name"`
	QType                 string   `json:"qtype"`
	Route                 string   `json:"route"`
	Rcode                 string   `json:"rcode"`
	Answers               []string `json:"answers"`
	LatencyMs             int64    `json:"latencyMs"`
	TS                    int64    `json:"ts"`
	ResolvedOutsideRoutes bool     `json:"resolvedOutsideRoutes,omitempty"`
	Error                 string   `json:"error,omitempty"`
}

// Proxy answers DNS queries, sending tunnel-bound ones through the SSH
// tunnel and the rest to the underlying network.
type Proxy struct {
	cfg      Config
	cls      *classifier
	upstream UpstreamFunc
	pool     *pool

	// OnEvent receives one Event per answered query. Optional.
	OnEvent func(Event)
	// OnTunnelHealth reports whether the intranet resolver answers through
	// the tunnel (false drives the DNS_UNREACHABLE warning). Optional.
	OnTunnelHealth func(ok bool)

	tunneled atomic.Uint64
	direct   atomic.Uint64
	healthMu sync.Mutex
	health   int // -1 unknown, 0 down, 1 up
}

// New creates a proxy. tunnel may be nil if cfg.Server is unset.
func New(cfg Config, tunnel TunnelDialFunc, upstream UpstreamFunc) *Proxy {
	if cfg.QueryTimeout <= 0 {
		cfg.QueryTimeout = 4 * time.Second
	}
	if cfg.PoolSize <= 0 {
		cfg.PoolSize = 2
	}
	p := &Proxy{
		cfg:      cfg,
		cls:      newClassifier(cfg.Suffixes, cfg.ReverseLookups, cfg.Routes),
		upstream: upstream,
		health:   -1,
	}
	if cfg.Server.IsValid() && tunnel != nil {
		server := netip.AddrPortFrom(cfg.Server, 53)
		p.pool = &pool{size: cfg.PoolSize, dial: func(ctx context.Context) (net.Conn, error) {
			return tunnel(ctx, server)
		}}
	}
	return p
}

// Counts returns how many queries went through the tunnel and directly.
func (p *Proxy) Counts() (tunneled, direct uint64) { return p.tunneled.Load(), p.direct.Load() }

// Reset drops the tunnel connections and forgets the resolver's health, e.g.
// after the SSH connection changed.
func (p *Proxy) Reset() {
	if p.pool != nil {
		p.pool.reset(false)
	}
	p.healthMu.Lock()
	p.health = -1
	p.healthMu.Unlock()
}

// Close drops the tunnel connections for good.
func (p *Proxy) Close() {
	if p.pool != nil {
		p.pool.reset(true)
	}
}

// HandleUDP answers a UDP query, truncating the reply to the client's
// advertised size (EDNS0, else 512) with TC set.
func (p *Proxy) HandleUDP(ctx context.Context, query []byte) []byte {
	q, reply := p.handle(ctx, query)
	if reply == nil {
		return nil
	}
	size := dns.MinMsgSize
	if q != nil {
		if opt := q.IsEdns0(); opt != nil && int(opt.UDPSize()) > size {
			size = int(opt.UDPSize())
		}
	}
	return pack(reply, size)
}

// ServeTCP serves RFC 7766 length-prefixed DNS on conn, answering queries
// concurrently and possibly out of order.
func (p *Proxy) ServeTCP(ctx context.Context, conn net.Conn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	var wmu sync.Mutex
	var wg sync.WaitGroup
	defer wg.Wait()
	var hdr [2]byte
	for {
		_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		if _, err := io.ReadFull(conn, hdr[:]); err != nil {
			return
		}
		msg := make([]byte, binary.BigEndian.Uint16(hdr[:]))
		if _, err := io.ReadFull(conn, msg); err != nil {
			return
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, reply := p.handle(ctx, msg)
			if reply == nil {
				return
			}
			out := pack(reply, dns.MaxMsgSize)
			if out == nil {
				return
			}
			buf := make([]byte, 2+len(out))
			binary.BigEndian.PutUint16(buf, uint16(len(out)))
			copy(buf[2:], out)
			wmu.Lock()
			_, _ = conn.Write(buf)
			wmu.Unlock()
		}()
	}
}

func pack(m *dns.Msg, size int) []byte {
	out, err := m.Pack()
	if err != nil {
		return nil
	}
	if len(out) > size {
		m.Truncate(size)
		if out, err = m.Pack(); err != nil {
			return nil
		}
	}
	return out
}

// handle parses and answers one query. It returns nil reply for input that
// isn't worth answering.
func (p *Proxy) handle(ctx context.Context, raw []byte) (*dns.Msg, *dns.Msg) {
	start := time.Now()
	q := new(dns.Msg)
	if err := q.Unpack(raw); err != nil {
		if len(raw) < 12 {
			return nil, nil
		}
		// Header parsed far enough to reply FORMERR with the right ID.
		r := new(dns.Msg)
		r.Id = binary.BigEndian.Uint16(raw)
		r.Response = true
		r.Rcode = dns.RcodeFormatError
		return nil, r
	}
	if q.Response || len(q.Question) == 0 {
		r := new(dns.Msg)
		r.SetRcode(q, dns.RcodeFormatError)
		return q, r
	}
	qn := q.Question[0]
	ev := Event{Name: qn.Name, QType: dns.TypeToString[qn.Qtype], TS: start.UnixMilli(), Answers: []string{}}

	var reply *dns.Msg
	var err error
	if p.cls.tunnelBound(qn.Name) {
		ev.Route = RouteTunnel
		p.tunneled.Add(1)
		reply, err = p.viaTunnel(ctx, q, raw)
	} else {
		ev.Route = RouteDirect
		p.direct.Add(1)
		reply, err = p.viaUpstream(ctx, q, raw)
	}
	if err != nil {
		ev.Error = err.Error()
		reply = new(dns.Msg)
		reply.SetRcode(q, dns.RcodeServerFailure)
	}
	reply.Id = q.Id
	ev.Rcode = dns.RcodeToString[reply.Rcode]
	ev.LatencyMs = time.Since(start).Milliseconds()
	for _, rr := range reply.Answer {
		switch a := rr.(type) {
		case *dns.A:
			ip, _ := netip.AddrFromSlice(a.A.To4())
			ev.Answers = append(ev.Answers, ip.String())
			if ev.Route == RouteTunnel && !p.cls.inRoutes(ip) {
				ev.ResolvedOutsideRoutes = true
			}
		case *dns.AAAA:
			ev.Answers = append(ev.Answers, a.AAAA.String())
		case *dns.CNAME:
			ev.Answers = append(ev.Answers, "CNAME "+a.Target)
		case *dns.PTR:
			ev.Answers = append(ev.Answers, "PTR "+a.Ptr)
		default:
			ev.Answers = append(ev.Answers, dns.TypeToString[rr.Header().Rrtype])
		}
	}
	if p.OnEvent != nil {
		p.OnEvent(ev)
	}
	return q, reply
}

var errNoServer = errors.New("no intranet DNS server configured")

func (p *Proxy) viaTunnel(ctx context.Context, q *dns.Msg, raw []byte) (*dns.Msg, error) {
	if p.cfg.HideAAAA && q.Question[0].Qtype == dns.TypeAAAA {
		// NODATA: the name may exist, but it has no address we can route.
		r := new(dns.Msg)
		r.SetReply(q)
		r.RecursionAvailable = true
		return r, nil
	}
	if p.pool == nil {
		return nil, errNoServer
	}
	ctx, cancel := context.WithTimeout(ctx, p.cfg.QueryTimeout)
	defer cancel()
	resp, err := p.pool.exchange(ctx, raw)
	p.setHealth(err == nil)
	if err != nil {
		return nil, err
	}
	return unpackReply(resp)
}

func (p *Proxy) viaUpstream(ctx context.Context, q *dns.Msg, raw []byte) (*dns.Msg, error) {
	if p.upstream == nil {
		return nil, errors.New("no upstream resolver")
	}
	ctx, cancel := context.WithTimeout(ctx, p.cfg.QueryTimeout)
	defer cancel()
	resp, err := p.upstream(ctx, raw)
	if err != nil {
		return nil, err
	}
	return unpackReply(resp)
}

func unpackReply(b []byte) (*dns.Msg, error) {
	r := new(dns.Msg)
	if err := r.Unpack(b); err != nil {
		return nil, err
	}
	return r, nil
}

func (p *Proxy) setHealth(ok bool) {
	v := 0
	if ok {
		v = 1
	}
	p.healthMu.Lock()
	changed := p.health != v
	p.health = v
	p.healthMu.Unlock()
	if changed && p.OnTunnelHealth != nil {
		p.OnTunnelHealth(ok)
	}
}
