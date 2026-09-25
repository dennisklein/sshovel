// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package mobile

import "golang.org/x/sys/unix"

// closeFd closes a TUN fd we were handed but won't use.
func closeFd(fd int32) {
	if fd >= 0 {
		unix.Close(int(fd))
	}
}
