// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package engine

import "time"

// Clock abstracts time so backoff can be tested without sleeping.
type Clock interface {
	Now() time.Time
	NewTimer(d time.Duration) Timer
}

// Timer is the subset of *time.Timer the engine uses.
type Timer interface {
	C() <-chan time.Time
	Stop() bool
}

type realClock struct{}

func (realClock) Now() time.Time { return time.Now() }

func (realClock) NewTimer(d time.Duration) Timer { return realTimer{time.NewTimer(d)} }

type realTimer struct{ t *time.Timer }

func (t realTimer) C() <-chan time.Time { return t.t.C }
func (t realTimer) Stop() bool          { return t.t.Stop() }

// Backoff parameters (ARCHITECTURE §6, Reconnect).
const (
	backoffMin    = time.Second
	backoffMax    = 30 * time.Second
	backoffJitter = 0.2
	stableAfter   = 60 * time.Second
)

// backoff returns the delay before reconnect attempt n (n ≥ 1): 1 s doubling
// to 30 s, with ±20 % jitter. r is uniform in [0, 1).
func backoff(n int, r float64) time.Duration {
	d := backoffMin
	for i := 1; i < n && d < backoffMax; i++ {
		d *= 2
	}
	d = min(d, backoffMax)
	f := 1 + backoffJitter*(2*r-1)
	return time.Duration(float64(d) * f)
}
