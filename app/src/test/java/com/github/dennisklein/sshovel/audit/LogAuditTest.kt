// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M8 log audit (ARCHITECTURE §9): release builds never log secrets, hosts or destinations. Every
 * logcat call in app/src/main either runs only in debug builds (`BuildConfig.DEBUG` on the same
 * line) or logs a fixed message, at most with an exception's class name: no other interpolation
 * and no throwable, whose message could quote hosts, JSON or key material.
 */
class LogAuditTest {
    private val call = Regex("""\bLog\.(v|d|i|w|e|wtf|println)\(""")
    private val allowedInterpolation = Regex("""\$\{e\.javaClass\.simpleName}""")

    private fun sources() = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun releaseLogsCarryNoData() {
        val files = sources()
        assertTrue("no sources found from ${File(".").absolutePath}", files.size > 20)
        val offenders = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (!call.containsMatchIn(line) || line.contains("BuildConfig.DEBUG")) return@mapIndexedNotNull null
                val args = line.substringAfter("(")
                val interpolates = args.replace(allowedInterpolation, "").contains('$')
                val passesThrowable = Regex(""",\s*(e|t|ex|throwable)\s*\)""").containsMatchIn(args)
                if (interpolates || passesThrowable) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("unguarded logcat calls that carry data:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }
}
