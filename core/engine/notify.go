// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package engine

import "sync"

// notifier delivers statuses to OnState in the order they were published,
// from one goroutine, without blocking the publisher. Publishers push while
// holding Engine.mu, so delivery order always matches e.status updates, and
// an OnState handler may call back into the engine (even Stop).
// Statuses pushed after stop are dropped.
type notifier struct {
	mu     sync.Mutex
	q      []Status
	closed bool
	wake   chan struct{}
}

func newNotifier(cb func(Status)) *notifier {
	n := &notifier{wake: make(chan struct{}, 1)}
	go n.run(cb)
	return n
}

func (n *notifier) push(s Status) {
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return
	}
	n.q = append(n.q, s)
	n.mu.Unlock()
	n.signal()
}

func (n *notifier) signal() {
	select {
	case n.wake <- struct{}{}:
	default:
	}
}

// stop lets the goroutine deliver what is queued and exit. It doesn't wait,
// so it may be called from an OnState handler.
func (n *notifier) stop() {
	n.mu.Lock()
	n.closed = true
	n.mu.Unlock()
	n.signal()
}

func (n *notifier) run(cb func(Status)) {
	for {
		n.mu.Lock()
		q, closed := n.q, n.closed
		n.q = nil
		n.mu.Unlock()
		for _, s := range q {
			if cb != nil {
				cb(s)
			}
		}
		if len(q) > 0 {
			continue
		}
		if closed {
			return
		}
		<-n.wake
	}
}
