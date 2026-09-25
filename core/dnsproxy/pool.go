// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package dnsproxy

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
)

// errBroken marks a channel failure: the caller reopens and retries once.
var errBroken = errors.New("dns channel broken")

// pool keeps up to size persistent DNS-over-TCP connections to the intranet
// resolver and pipelines queries on them (RFC 7766).
type pool struct {
	dial func(ctx context.Context) (net.Conn, error)
	size int

	mu      sync.Mutex
	pipes   []*pipe
	dialing chan struct{} // non-nil while a pipe is being opened
	closed  bool
}

func (p *pool) exchange(ctx context.Context, msg []byte) ([]byte, error) {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		pp, err := p.get(ctx)
		if err != nil {
			lastErr = err
			if ctx.Err() != nil {
				break
			}
			continue
		}
		resp, err := pp.exchange(ctx, msg)
		if err == nil {
			return resp, nil
		}
		lastErr = err
		if !errors.Is(err, errBroken) {
			break
		}
		p.drop(pp)
	}
	return nil, lastErr
}

// get returns the least busy pipe, opening a new one while the pool has
// room and every open pipe has queries in flight. Only one pipe is opened at
// a time; callers with nothing to use wait for it.
func (p *pool) get(ctx context.Context) (*pipe, error) {
	for {
		p.mu.Lock()
		if p.closed {
			p.mu.Unlock()
			return nil, errors.New("dns pool closed")
		}
		var best *pipe
		for _, pp := range p.pipes {
			if best == nil || pp.inflight() < best.inflight() {
				best = pp
			}
		}
		if best != nil && (best.inflight() == 0 || len(p.pipes) >= p.size || p.dialing != nil) {
			p.mu.Unlock()
			return best, nil
		}
		if wait := p.dialing; wait != nil {
			p.mu.Unlock()
			select {
			case <-wait:
				continue
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
		done := make(chan struct{})
		p.dialing = done
		p.mu.Unlock()

		conn, err := p.dial(ctx)

		p.mu.Lock()
		p.dialing = nil
		close(done)
		if err != nil {
			p.mu.Unlock()
			if best != nil {
				return best, nil
			}
			return nil, err
		}
		pp := newPipe(conn)
		if p.closed {
			p.mu.Unlock()
			pp.close(errors.New("pool closed"))
			return nil, errors.New("dns pool closed")
		}
		p.pipes = append(p.pipes, pp)
		p.mu.Unlock()
		go func() {
			<-pp.dead
			p.drop(pp)
		}()
		return pp, nil
	}
}

func (p *pool) drop(pp *pipe) {
	pp.close(errBroken)
	p.mu.Lock()
	defer p.mu.Unlock()
	for i, x := range p.pipes {
		if x == pp {
			p.pipes = append(p.pipes[:i], p.pipes[i+1:]...)
			return
		}
	}
}

// reset closes all pipes; new ones are opened on demand. When closed is true
// the pool refuses further queries.
func (p *pool) reset(closed bool) {
	p.mu.Lock()
	pipes := p.pipes
	p.pipes = nil
	p.closed = p.closed || closed
	p.mu.Unlock()
	for _, pp := range pipes {
		pp.close(errBroken)
	}
}

// pipe is one DNS-over-TCP connection with message-ID rewriting, so any
// number of clients' queries (which may share IDs) can be in flight at once.
type pipe struct {
	conn net.Conn
	wmu  sync.Mutex

	mu      sync.Mutex
	pending map[uint16]chan []byte
	nextID  uint16
	err     error
	dead    chan struct{}
}

func newPipe(conn net.Conn) *pipe {
	pp := &pipe{conn: conn, pending: map[uint16]chan []byte{}, dead: make(chan struct{})}
	go pp.read()
	return pp
}

func (pp *pipe) inflight() int {
	pp.mu.Lock()
	defer pp.mu.Unlock()
	return len(pp.pending)
}

func (pp *pipe) close(err error) {
	pp.mu.Lock()
	defer pp.mu.Unlock()
	if pp.err == nil {
		pp.err = err
		close(pp.dead)
		pp.conn.Close()
	}
}

func (pp *pipe) read() {
	var hdr [2]byte
	for {
		if _, err := io.ReadFull(pp.conn, hdr[:]); err != nil {
			pp.close(fmt.Errorf("%w: %v", errBroken, err))
			return
		}
		msg := make([]byte, binary.BigEndian.Uint16(hdr[:]))
		if _, err := io.ReadFull(pp.conn, msg); err != nil {
			pp.close(fmt.Errorf("%w: %v", errBroken, err))
			return
		}
		if len(msg) < 2 {
			continue
		}
		id := binary.BigEndian.Uint16(msg)
		pp.mu.Lock()
		ch := pp.pending[id]
		delete(pp.pending, id)
		pp.mu.Unlock()
		if ch != nil {
			ch <- msg // buffered; late answers for timed-out queries are dropped
		}
	}
}

// exchange sends msg with a pipe-unique ID and returns the response with the
// ID still rewritten; the caller restores the client's ID.
func (pp *pipe) exchange(ctx context.Context, msg []byte) ([]byte, error) {
	if len(msg) < 12 || len(msg) > 65535 {
		return nil, errors.New("bad DNS message size")
	}
	ch := make(chan []byte, 1)
	pp.mu.Lock()
	if pp.err != nil {
		pp.mu.Unlock()
		return nil, errBroken
	}
	if len(pp.pending) >= 65536 {
		pp.mu.Unlock()
		return nil, errors.New("too many queries in flight")
	}
	id := pp.nextID
	for pp.pending[id] != nil {
		id++
	}
	pp.nextID = id + 1
	pp.pending[id] = ch
	pp.mu.Unlock()
	defer func() {
		pp.mu.Lock()
		if pp.pending[id] == ch {
			delete(pp.pending, id)
		}
		pp.mu.Unlock()
	}()

	buf := make([]byte, 2+len(msg))
	binary.BigEndian.PutUint16(buf, uint16(len(msg)))
	copy(buf[2:], msg)
	binary.BigEndian.PutUint16(buf[2:], id)
	pp.wmu.Lock()
	dl, _ := ctx.Deadline() // zero means none
	_ = pp.conn.SetWriteDeadline(dl)
	_, err := pp.conn.Write(buf)
	pp.wmu.Unlock()
	if err != nil {
		pp.close(fmt.Errorf("%w: %v", errBroken, err))
		return nil, errBroken
	}
	select {
	case resp := <-ch:
		return resp, nil
	case <-pp.dead:
		return nil, errBroken
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}
