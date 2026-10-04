// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import android.content.Context
import com.github.dennisklein.sshovel.R

/** The copy for a validation message (handoff strings err_*). */
fun FieldMsg.text(context: Context): String {
    fun s(id: Int, vararg a: Any) = context.getString(id, *a)
    return when (kind) {
        FieldMsg.Kind.REQUIRED -> s(R.string.err_required)
        FieldMsg.Kind.HOST -> s(R.string.err_host_invalid)
        FieldMsg.Kind.PORT -> s(R.string.err_port)
        FieldMsg.Kind.KEY -> s(R.string.err_key_required)
        FieldMsg.Kind.ROUTES_REQUIRED -> s(R.string.err_routes_required)
        FieldMsg.Kind.CIDR_INVALID -> s(R.string.err_cidr_invalid, args.firstOrNull() ?: "10.0.0.0/8")
        FieldMsg.Kind.CIDR_NOT_CANONICAL -> s(R.string.err_cidr_not_canonical, args.firstOrNull().orEmpty())
        FieldMsg.Kind.COVERED -> s(R.string.err_cidr_covered, args.firstOrNull().orEmpty())
        FieldMsg.Kind.DUPLICATE -> s(R.string.err_duplicate)
        FieldMsg.Kind.TUN_OVERLAP -> s(R.string.err_tunnel_overlap, args.getOrElse(0) { "" }, args.getOrElse(1) { "198.18.0.0/24" })
        FieldMsg.Kind.LAN_OVERLAP -> s(R.string.err_lan_overlap, args.firstOrNull().orEmpty())
        FieldMsg.Kind.DNS_REQUIRED -> s(R.string.err_dns_required)
        FieldMsg.Kind.IP_INVALID -> s(R.string.err_ip_invalid)
        FieldMsg.Kind.DOMAIN_INVALID -> s(R.string.err_domain_invalid)
        FieldMsg.Kind.NO_APPS -> s(R.string.err_no_apps)
        FieldMsg.Kind.RANGE -> s(R.string.err_range, args[0].toInt(), args[1].toInt())
        FieldMsg.Kind.NUMBER -> s(R.string.field_invalid_number)
        FieldMsg.Kind.MTU -> s(R.string.err_range, 1280, 9000)
    }
}

/** "the host and 1 subnet" for the discard dialog (handoff P5). */
fun Changes.text(context: Context): String {
    val words = parts.map { c ->
        when (c) {
            Change.NAME -> context.getString(R.string.changed_name)
            Change.HOST -> context.getString(R.string.changed_host)
            Change.PORT -> context.getString(R.string.changed_port)
            Change.USER -> context.getString(R.string.changed_user)
            Change.KEY -> context.getString(R.string.changed_key)
            Change.SUBNETS -> context.resources.getQuantityString(R.plurals.changed_subnets, subnets, subnets)
            Change.DNS -> context.getString(R.string.changed_dns)
            Change.APPS -> context.getString(R.string.changed_apps)
            Change.ADVANCED -> context.getString(R.string.changed_advanced)
        }
    }
    return when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> context.getString(R.string.changed_fields_and, words.dropLast(1).joinToString(", "), words.last())
    }
}
