// SPDX-FileCopyrightText: 2026 <Copyright holder>
// SPDX-License-Identifier: GPL-3.0-or-later

package mobile

import "testing"

func TestHello(t *testing.T) {
	msg, err := Hello()
	if err != nil {
		t.Fatal(err)
	}
	t.Log(msg)
}
