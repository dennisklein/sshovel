// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package netstack runs a gVisor TCP/IP stack on the TUN device and turns
// app TCP connections into SSH direct-tcpip channels (ARCHITECTURE §4).
package netstack

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"

	"github.com/dennisklein/sshovel/core/errcode"
)

// DialFunc opens an upstream connection for an app flow, normally an SSH
// direct-tcpip channel. Errors should carry an errcode flow reason.
type DialFunc func(ctx context.Context, dst netip.AddrPort) (net.Conn, error)

// DNSHandler answers queries sent to the DNS virtual IP.
type DNSHandler interface {
	// HandleUDP returns the reply to a UDP query, or nil to stay silent.
	HandleUDP(ctx context.Context, query []byte) []byte
	// ServeTCP serves length-prefixed DNS on conn until it closes.
	ServeTCP(ctx context.Context, conn net.Conn)
}

// Options configures the stack.
type Options struct {
	MTU            uint32
	TunPrefix      netip.Prefix // the TUN subnet, e.g. 10.99.0.0/24
	DNSVirtualIP   netip.Addr
	ConnectTimeout time.Duration
	Dial           DialFunc
	DNS            DNSHandler
	OnFlow         func(FlowEvent) // optional
}

// Flow event kinds.
const (
	FlowOpen  = "open"
	FlowClose = "close"
	FlowFail  = "fail"
)

// FlowEvent is emitted for every forwarded TCP connection.
type FlowEvent struct {
	ID         uint64 `json:"id"`
	Event      string `json:"event"`
	Src        string `json:"src"`
	Dst        string `json:"dst"`
	Reason     string `json:"reason,omitempty"`
	BytesIn    uint64 `json:"bytesIn"`  // server → app
	BytesOut   uint64 `json:"bytesOut"` // app → server
	DurationMs int64  `json:"durationMs"`
	TS         int64  `json:"ts"` // unix ms
}

// Stats are the stack's counters (ARCHITECTURE §7, Stats fields).
type Stats struct {
	BytesIn     uint64
	BytesOut    uint64
	ActiveFlows int64
	DroppedUDP  uint64
	DroppedICMP uint64
}

// Stack is a running netstack.
type Stack struct {
	o      Options
	s      *stack.Stack
	f      *filter
	ctx    context.Context
	cancel context.CancelFunc

	nextID     atomic.Uint64
	bytesIn    atomic.Uint64
	bytesOut   atomic.Uint64
	active     atomic.Int64
	droppedUDP atomic.Uint64

	mu    sync.Mutex
	flows map[uint64]io.Closer
	wg    sync.WaitGroup
}

const nicID tcpip.NICID = 1

// udpIdle is how long a DNS UDP flow lingers after its last query. Clients
// retry on the same socket within a few seconds.
const udpIdle = 10 * time.Second

// New builds a stack on ep, which it owns from now on.
func New(ep stack.LinkEndpoint, o Options) (*Stack, error) {
	if o.Dial == nil {
		return nil, errors.New("netstack: Dial is required")
	}
	if o.ConnectTimeout <= 0 {
		o.ConnectTimeout = 10 * time.Second
	}
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)

	ctx, cancel := context.WithCancel(context.Background())
	n := &Stack{o: o, s: s, ctx: ctx, cancel: cancel, flows: map[uint64]io.Closer{}}
	// Handlers go in before the NIC exists: a real TUN starts delivering
	// packets the moment it is attached.
	tf := tcp.NewForwarder(s, 0, 1024, n.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tf.HandlePacket)
	uf := udp.NewForwarder(s, n.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, uf.HandlePacket)

	n.f = newFilter(ep)
	if err := s.CreateNIC(nicID, n.f); err != nil {
		cancel()
		s.Close()
		return nil, fmt.Errorf("netstack: CreateNIC: %s", err)
	}
	// Accept packets for any destination and send from any source.
	s.SetPromiscuousMode(nicID, true)
	s.SetSpoofing(nicID, true)
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nicID}})
	return n, nil
}

// Close closes all flows and destroys the stack and its link endpoint.
func (n *Stack) Close() {
	n.cancel()
	n.CloseFlows()
	n.s.Close()
	n.s.Wait()
	n.wg.Wait()
}

// CloseFlows closes every forwarded TCP connection, e.g. when the SSH
// connection they ride on has died.
func (n *Stack) CloseFlows() {
	n.mu.Lock()
	cs := make([]io.Closer, 0, len(n.flows))
	for _, c := range n.flows {
		cs = append(cs, c)
	}
	n.mu.Unlock()
	for _, c := range cs {
		c.Close()
	}
}

// Stats returns a snapshot of the counters.
func (n *Stack) Stats() Stats {
	return Stats{
		BytesIn:     n.bytesIn.Load(),
		BytesOut:    n.bytesOut.Load(),
		ActiveFlows: n.active.Load(),
		DroppedUDP:  n.droppedUDP.Load(),
		DroppedICMP: n.f.droppedICMP.Load(),
	}
}

func addrPort(a tcpip.Address, port uint16) netip.AddrPort {
	ip, _ := netip.AddrFromSlice(a.AsSlice())
	return netip.AddrPortFrom(ip.Unmap(), port)
}

func (n *Stack) emit(ev FlowEvent) {
	if n.o.OnFlow != nil {
		ev.TS = time.Now().UnixMilli()
		n.o.OnFlow(ev)
	}
}

func (n *Stack) handleTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := addrPort(id.LocalAddress, id.LocalPort)
	src := addrPort(id.RemoteAddress, id.RemotePort)

	if dst.Addr() == n.o.DNSVirtualIP {
		// TCP/53 is ours; everything else (notably 853, Private DNS) gets an
		// immediate RST so Android falls back to port 53 quickly.
		if dst.Port() != 53 || n.o.DNS == nil {
			r.Complete(true)
			return
		}
		conn := accept(r)
		if conn == nil {
			return
		}
		n.wg.Add(1)
		go func() {
			defer n.wg.Done()
			defer conn.Close()
			n.o.DNS.ServeTCP(n.ctx, conn)
		}()
		return
	}
	if n.o.TunPrefix.IsValid() && n.o.TunPrefix.Contains(dst.Addr()) {
		r.Complete(true) // nothing lives in the TUN subnet
		return
	}

	fid := n.nextID.Add(1)
	start := time.Now()
	ctx, cancel := context.WithTimeout(n.ctx, n.o.ConnectTimeout)
	up, err := n.o.Dial(ctx, dst)
	cancel()
	if err != nil {
		// Connect-then-accept: the app sees a RST, never a connection that
		// dies after its SYN-ACK.
		r.Complete(true)
		n.emit(FlowEvent{ID: fid, Event: FlowFail, Src: src.String(), Dst: dst.String(),
			Reason: string(errcode.Of(err)), DurationMs: time.Since(start).Milliseconds()})
		return
	}
	conn := accept(r)
	if conn == nil {
		up.Close()
		return
	}
	n.relay(fid, src, dst, conn, up)
}

func accept(r *tcp.ForwarderRequest) *gonet.TCPConn {
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		r.Complete(true)
		return nil
	}
	r.Complete(false)
	return gonet.NewTCPConn(&wq, ep)
}

type closerFunc func() error

func (f closerFunc) Close() error { return f() }

func (n *Stack) relay(id uint64, src, dst netip.AddrPort, app *gonet.TCPConn, up net.Conn) {
	var once sync.Once
	closeBoth := func() error {
		once.Do(func() { app.Close(); up.Close() })
		return nil
	}
	n.mu.Lock()
	n.flows[id] = closerFunc(closeBoth)
	n.mu.Unlock()
	n.active.Add(1)
	n.emit(FlowEvent{ID: id, Event: FlowOpen, Src: src.String(), Dst: dst.String()})
	start := time.Now()

	var in, out atomic.Uint64
	var wg sync.WaitGroup
	wg.Add(2)
	pipe := func(dstW net.Conn, srcR net.Conn, ctr, total *atomic.Uint64) {
		defer wg.Done()
		_, err := io.Copy(&countingWriter{w: dstW, a: ctr, b: total}, srcR)
		if err != nil {
			closeBoth() // reset or tunnel death: tear down both directions
			return
		}
		closeWrite(dstW) // half-close: propagate EOF
	}
	go pipe(up, app, &out, &n.bytesOut)
	go pipe(app, up, &in, &n.bytesIn)
	wg.Wait()
	closeBoth()

	n.mu.Lock()
	delete(n.flows, id)
	n.mu.Unlock()
	n.active.Add(-1)
	n.emit(FlowEvent{ID: id, Event: FlowClose, Src: src.String(), Dst: dst.String(),
		BytesIn: in.Load(), BytesOut: out.Load(), DurationMs: time.Since(start).Milliseconds()})
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		if cw.CloseWrite() == nil {
			return
		}
	}
	c.Close()
}

type countingWriter struct {
	w    io.Writer
	a, b *atomic.Uint64
}

func (c *countingWriter) Write(p []byte) (int, error) {
	k, err := c.w.Write(p)
	c.a.Add(uint64(k))
	c.b.Add(uint64(k))
	return k, err
}

func (n *Stack) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := addrPort(id.LocalAddress, id.LocalPort)
	if dst.Addr() != n.o.DNSVirtualIP || dst.Port() != 53 || n.o.DNS == nil {
		// Not handled: gVisor answers with ICMP port unreachable, so QUIC and
		// friends fall back to TCP without waiting for a timeout.
		n.droppedUDP.Add(1)
		return false
	}
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return true
	}
	conn := gonet.NewUDPConn(&wq, ep)
	n.wg.Add(1)
	go func() {
		defer n.wg.Done()
		n.serveDNSUDP(conn)
	}()
	return true
}

func (n *Stack) serveDNSUDP(conn *gonet.UDPConn) {
	defer conn.Close()
	stop := context.AfterFunc(n.ctx, func() { conn.Close() })
	defer stop()
	var wg sync.WaitGroup
	defer wg.Wait()
	buf := make([]byte, 65535)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(udpIdle))
		k, err := conn.Read(buf)
		if err != nil {
			return
		}
		q := append([]byte(nil), buf[:k]...)
		wg.Add(1)
		go func() {
			defer wg.Done()
			if resp := n.o.DNS.HandleUDP(n.ctx, q); resp != nil {
				_, _ = conn.Write(resp)
			}
		}()
	}
}
