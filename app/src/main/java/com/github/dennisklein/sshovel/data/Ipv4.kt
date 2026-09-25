// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import java.net.InetAddress

/** An IPv4 prefix such as 10.77.0.0/24. Validation proper lives in Go (ValidateConfig). */
data class Ipv4Prefix(val address: InetAddress, val length: Int) {
    /** The first host address, used for the TUN interface (198.18.0.0/24 → 198.18.0.1). */
    fun firstHost(): InetAddress {
        val b = address.address
        val n = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        val h = n + 1
        return InetAddress.getByAddress(byteArrayOf((h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte()))
    }

    companion object {
        private val literal = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""")

        fun parse(cidr: String): Ipv4Prefix {
            val m = requireNotNull(literal.matchEntire(cidr.trim())) { "not an IPv4 CIDR: $cidr" }
            val octets = m.groupValues.subList(1, 5).map { it.toInt() }
            val length = m.groupValues[5].toInt()
            require(octets.all { it in 0..255 } && length in 0..32) { "not an IPv4 CIDR: $cidr" }
            // Literal bytes: never triggers a DNS lookup.
            return Ipv4Prefix(InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() }), length)
        }
    }
}
