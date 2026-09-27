// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import com.github.dennisklein.sshovel.data.Apps
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.data.Tun
import com.github.dennisklein.sshovel.data.ValidationIssue
import com.github.dennisklein.sshovel.keys.KeyEntry

/**
 * The profile editor's working copy (DESIGN_BRIEF §5.3). Text fields stay strings until saved so
 * that "22x" can be shown with an error instead of being lost. No Android dependencies: the
 * mapping to [Profile] and the validation messages are unit-tested.
 */
data class ProfileDraft(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val keyId: String? = null,
    val routes: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val dnsServer: String = "",
    val suffixes: List<String> = emptyList(),
    val searchDomains: List<String> = emptyList(),
    val reverseLookups: Boolean = true,
    val hideAAAA: Boolean = true,
    val appsMode: String = Apps.ALL,
    val packages: List<String> = emptyList(),
    val keepalive: String = "20",
    val timeout: String = "10",
    val tunCidr: String = Tun().cidr,
    val mtu: String = Tun().mtu.toString(),
    val isDefault: Boolean = false,
) {
    /** The profile to validate and save; the stored host key pin is never taken from here. */
    fun toProfile(keys: List<KeyEntry>): Profile {
        val key = keys.firstOrNull { it.id == keyId }
        val auth = when {
            key != null -> Auth(kind = key.kind, alias = key.id, publicKeyPkix = key.publicKeyPkix)
            else -> Auth(kind = "", alias = "")
        }
        return Profile(
            id = id,
            name = name.trim(),
            server = Server(host.trim(), port.trim().toIntOrNull() ?: 0, user.trim()),
            auth = auth,
            routes = routes,
            excludedRoutes = excluded,
            dns = Dns(
                server = dnsServer.trim().ifEmpty { null },
                suffixes = suffixes,
                searchDomains = searchDomains,
                reverseLookups = reverseLookups,
                hideAAAA = hideAAAA,
            ),
            apps = Apps(appsMode, if (appsMode == Apps.ALL) emptyList() else packages),
            tun = Tun(cidr = tunCidr.trim(), dnsVirtualIp = dnsVirtualIpFor(tunCidr.trim()), mtu = mtu.trim().toIntOrNull() ?: 0),
            keepaliveSec = keepalive.trim().toIntOrNull() ?: 0,
            connectTimeoutSec = timeout.trim().toIntOrNull() ?: 0,
        )
    }

    companion object {
        fun of(p: Profile, isDefault: Boolean) = ProfileDraft(
            id = p.id, name = p.name, host = p.server.host, port = p.server.port.toString(), user = p.server.user,
            keyId = p.auth.alias.ifEmpty { null }, routes = p.routes, excluded = p.excludedRoutes,
            dnsServer = p.dns.server.orEmpty(), suffixes = p.dns.suffixes, searchDomains = p.dns.searchDomains,
            reverseLookups = p.dns.reverseLookups, hideAAAA = p.dns.hideAAAA,
            appsMode = p.apps.mode, packages = p.apps.packages,
            keepalive = p.keepaliveSec.toString(), timeout = p.connectTimeoutSec.toString(),
            tunCidr = p.tun.cidr, mtu = p.tun.mtu.toString(), isDefault = isDefault,
        )

        /**
         * The DNS virtual IP for a tunnel subnet: .53 of the network when it fits (the default
         * 198.18.0.53), else the second host. The editor doesn't expose it (handoff P4).
         */
        fun dnsVirtualIpFor(cidr: String): String {
            val p = Cidr.parse(cidr) ?: return Tun().dnsVirtualIp
            val size = 1L shl (32 - p.bits)
            val offset = if (size > 64) 53L else 2L
            return Cidr.format(p.network + offset)
        }
    }
}

/** A minimal IPv4 CIDR parser for input checks; the rules proper live in Go (ARCHITECTURE §3). */
data class Cidr(val network: Long, val bits: Int, val address: Long) {
    val canonical: Boolean get() = network == address
    override fun toString() = "${format(network)}/$bits"

    fun contains(o: Cidr): Boolean = bits <= o.bits && (o.network and mask(bits)) == network

    companion object {
        private val re = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""")

        fun parse(s: String): Cidr? {
            val m = re.matchEntire(s.trim()) ?: return null
            val o = m.groupValues.subList(1, 5).map { it.toInt() }
            val bits = m.groupValues[5].toInt()
            if (o.any { it > 255 } || bits > 32) return null
            val a = o.fold(0L) { acc, x -> (acc shl 8) or x.toLong() }
            return Cidr(a and mask(bits), bits, a)
        }

        /** The address part of [s] if it is a dotted quad, e.g. for "e.g. 10.40.0.0/16". */
        fun addressOf(s: String): String? {
            val a = s.trim().substringBefore('/')
            val o = a.split('.')
            return a.takeIf { o.size == 4 && o.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true } }
        }

        fun mask(bits: Int): Long = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL

        fun format(a: Long): String = "${(a shr 24) and 255}.${(a shr 16) and 255}.${(a shr 8) and 255}.${a and 255}"
    }
}

/** A validation message for a field, without Android resources (mapped to strings in the UI). */
data class FieldMsg(val kind: Kind, val args: List<String> = emptyList(), val warning: Boolean = false) {
    enum class Kind {
        REQUIRED, HOST, PORT, KEY, ROUTES_REQUIRED, CIDR_INVALID, CIDR_NOT_CANONICAL, COVERED, DUPLICATE,
        TUN_OVERLAP, DNS_REQUIRED, IP_INVALID, DOMAIN_INVALID, NO_APPS, RANGE, NUMBER, MTU,
    }
}

/**
 * Editor field keys: [NAME] … and indexed "routes[i]" / "excluded[i]". [TUN] also receives the
 * Go core's TUN_OVERLAPS_ROUTE, which it reports on the route (ARCHITECTURE §8).
 */
object Fields {
    const val NAME = "name"
    const val HOST = "host"
    const val PORT = "port"
    const val USER = "user"
    const val KEY = "key"
    const val ROUTES = "routes"
    const val DNS = "dns"
    const val SUFFIXES = "suffixes"
    const val SEARCH = "search"
    const val APPS = "apps"
    const val KEEPALIVE = "keepalive"
    const val TIMEOUT = "timeout"
    const val TUN = "tun"
    const val MTU = "mtu"

    fun route(i: Int) = "routes[$i]"
    fun excluded(i: Int) = "excluded[$i]"

    /** Field order on screen, for "Show first" (handoff P3). */
    fun order(key: String): Int = when {
        key == NAME -> 0
        key == HOST -> 1
        key == PORT -> 2
        key == USER -> 3
        key == KEY -> 4
        key == ROUTES -> 5
        key.startsWith("routes[") -> 6
        key.startsWith("excluded[") -> 7
        key == DNS -> 8
        key == SUFFIXES -> 9
        key == SEARCH -> 10
        key == APPS -> 11
        key == KEEPALIVE -> 12
        key == TIMEOUT -> 13
        key == TUN -> 14
        else -> 15
    }
}

/**
 * Field messages for [draft]: local checks (numbers, name, key) plus the Go core's issues for the
 * built profile. Errors block saving; warnings (a subnet already covered by another) don't.
 */
fun fieldMessages(draft: ProfileDraft, issues: List<ValidationIssue>): Map<String, FieldMsg> {
    val out = linkedMapOf<String, FieldMsg>()
    fun put(key: String, msg: FieldMsg) {
        val old = out[key]
        if (old == null || (old.warning && !msg.warning)) out[key] = msg
    }
    if (draft.name.isBlank()) put(Fields.NAME, FieldMsg(FieldMsg.Kind.REQUIRED))
    if (draft.keyId == null) put(Fields.KEY, FieldMsg(FieldMsg.Kind.KEY))
    listOf(Fields.PORT to draft.port, Fields.KEEPALIVE to draft.keepalive, Fields.TIMEOUT to draft.timeout, Fields.MTU to draft.mtu)
        .forEach { (k, v) -> if (v.trim().toIntOrNull() == null) put(k, FieldMsg(FieldMsg.Kind.NUMBER)) }

    val indexed = Regex("""^(\w+(?:\.\w+)?)\[(\d+)]$""")
    for (issue in issues) {
        val m = indexed.matchEntire(issue.field)
        val base = m?.groupValues?.get(1) ?: issue.field
        val i = m?.groupValues?.get(2)?.toInt() ?: -1
        val warning = !issue.isError
        when (base) {
            "server.host" -> put(Fields.HOST, FieldMsg(if (issue.code == "REQUIRED") FieldMsg.Kind.REQUIRED else FieldMsg.Kind.HOST))
            "server.port" -> put(Fields.PORT, FieldMsg(FieldMsg.Kind.PORT))
            "server.user" -> put(Fields.USER, FieldMsg(FieldMsg.Kind.REQUIRED))
            "auth.alias", "auth.kind", "auth.publicKeyPkix" -> put(Fields.KEY, FieldMsg(FieldMsg.Kind.KEY))
            "routes" -> if (i < 0) {
                put(Fields.ROUTES, FieldMsg(FieldMsg.Kind.ROUTES_REQUIRED))
            } else {
                val route = draft.routes.getOrNull(i).orEmpty()
                when (issue.code) {
                    "TUN_OVERLAPS_ROUTE" -> put(Fields.TUN, FieldMsg(FieldMsg.Kind.TUN_OVERLAP, listOf(route, Tun().cidr)))
                    "NOT_CANONICAL" -> put(Fields.route(i), FieldMsg(FieldMsg.Kind.CIDR_NOT_CANONICAL, listOf(issue.suggestion.orEmpty())))
                    "DUPLICATE" -> put(Fields.route(i), FieldMsg(FieldMsg.Kind.DUPLICATE, warning = true))
                    "ROUTE_OVERLAP" -> {
                        // Prefixes nest: the shorter one covers the other. Mark the covered row.
                        val cover = issue.suggestion.orEmpty()
                        if (cover != route) {
                            put(Fields.route(i), FieldMsg(FieldMsg.Kind.COVERED, listOf(cover), warning = true))
                        } else {
                            val covered = draft.routes.indices.firstOrNull { j ->
                                j < i && Cidr.parse(cover)?.let { c -> Cidr.parse(draft.routes[j])?.let(c::contains) } == true
                            }
                            if (covered != null) put(Fields.route(covered), FieldMsg(FieldMsg.Kind.COVERED, listOf(cover), warning = true))
                        }
                    }
                    else -> put(Fields.route(i), FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOfNotNull(exampleFor(route))))
                }
            }
            "excludedRoutes" -> put(
                Fields.excluded(i),
                if (issue.code == "NOT_CANONICAL") FieldMsg(FieldMsg.Kind.CIDR_NOT_CANONICAL, listOf(issue.suggestion.orEmpty()))
                else FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOfNotNull(exampleFor(draft.excluded.getOrNull(i).orEmpty()))),
            )
            "dns.server" -> put(Fields.DNS, FieldMsg(if (issue.code == "DNS_SERVER_MISSING") FieldMsg.Kind.DNS_REQUIRED else FieldMsg.Kind.IP_INVALID))
            "dns.suffixes" -> put(Fields.SUFFIXES, FieldMsg(FieldMsg.Kind.DOMAIN_INVALID))
            "dns.searchDomains" -> put(Fields.SEARCH, FieldMsg(FieldMsg.Kind.DOMAIN_INVALID))
            "apps.packages", "apps.mode" -> put(Fields.APPS, FieldMsg(FieldMsg.Kind.NO_APPS))
            "keepaliveSec" -> put(Fields.KEEPALIVE, FieldMsg(FieldMsg.Kind.RANGE, listOf("5", "300")))
            "connectTimeoutSec" -> put(Fields.TIMEOUT, FieldMsg(FieldMsg.Kind.RANGE, listOf("1", "120")))
            "tun.cidr" -> put(
                Fields.TUN,
                if (issue.code == "NOT_CANONICAL") FieldMsg(FieldMsg.Kind.CIDR_NOT_CANONICAL, listOf(issue.suggestion.orEmpty()))
                else FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOfNotNull(exampleFor(draft.tunCidr) ?: "198.18.0.0")),
            )
            "tun.dnsVirtualIp" -> put(Fields.TUN, FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOf("198.18.0.0")))
            "tun.mtu" -> put(Fields.MTU, FieldMsg(FieldMsg.Kind.RANGE, listOf("1280", "9000")))
        }
    }
    return out
}

/** Checks a subnet typed into an add field before it joins [existing]. Null: fine. */
fun checkNewCidr(input: String, existing: List<String>): FieldMsg? {
    val c = Cidr.parse(input) ?: return FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOf(exampleFor(input) ?: "10.0.0.0"))
    if (!c.canonical) return FieldMsg(FieldMsg.Kind.CIDR_NOT_CANONICAL, listOf(c.toString()))
    if (existing.any { it == c.toString() }) return FieldMsg(FieldMsg.Kind.DUPLICATE)
    return null
}

/** "10.40.0.0/33" → "10.40.0.0/16": the handoff's example in the invalid-CIDR message. */
fun exampleFor(input: String): String? = Cidr.addressOf(input)?.let { "$it/16" }

/** Domain names in suffix and search chips: letters, digits, hyphens, dots; no scheme. */
fun isValidDomain(s: String): Boolean =
    s.length in 1..253 && s.trim('.').split('.').all { l -> l.isNotEmpty() && l.length <= 63 && l.all { it.isLetterOrDigit() || it == '-' } && !l.startsWith('-') && !l.endsWith('-') }

/** What changed between [a] and [b], for "You changed the host and 1 subnet" (handoff P5). */
enum class Change { NAME, HOST, PORT, USER, KEY, SUBNETS, DNS, APPS, ADVANCED }

data class Changes(val parts: List<Change>, val subnets: Int)

fun changesBetween(a: ProfileDraft, b: ProfileDraft): Changes {
    val parts = mutableListOf<Change>()
    if (a.name.trim() != b.name.trim()) parts += Change.NAME
    if (a.host.trim() != b.host.trim()) parts += Change.HOST
    if (a.port.trim() != b.port.trim()) parts += Change.PORT
    if (a.user.trim() != b.user.trim()) parts += Change.USER
    if (a.keyId != b.keyId) parts += Change.KEY
    val subnets = (a.routes.toSet() xor b.routes.toSet()).size + (a.excluded.toSet() xor b.excluded.toSet()).size
    if (subnets > 0) parts += Change.SUBNETS
    if (a.dnsServer.trim() != b.dnsServer.trim() || a.suffixes != b.suffixes || a.searchDomains != b.searchDomains ||
        a.reverseLookups != b.reverseLookups || a.hideAAAA != b.hideAAAA
    ) {
        parts += Change.DNS
    }
    if (a.appsMode != b.appsMode || a.packages.toSet() != b.packages.toSet()) parts += Change.APPS
    if (a.keepalive.trim() != b.keepalive.trim() || a.timeout.trim() != b.timeout.trim() || a.tunCidr.trim() != b.tunCidr.trim() ||
        a.mtu.trim() != b.mtu.trim() || a.isDefault != b.isDefault
    ) {
        parts += Change.ADVANCED
    }
    return Changes(parts, subnets)
}

private infix fun <T> Set<T>.xor(o: Set<T>): Set<T> = (this - o) + (o - this)
