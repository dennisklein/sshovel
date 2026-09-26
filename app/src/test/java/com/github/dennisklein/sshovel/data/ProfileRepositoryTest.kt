// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import com.github.dennisklein.sshovel.TestStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant

class ProfileRepositoryTest {
    private val t = TestStores()
    private val clock = Instant.parse("2026-09-27T10:00:00Z")
    private val repo = ProfileRepository(t.store, t.scope) { clock }
    private val keyA = HostKeyInfo("ssh-ed25519", "SHA256:aaaa")
    private val keyB = HostKeyInfo("ssh-ed25519", "SHA256:bbbb")

    @After fun tearDown() = t.close()

    private fun profile(name: String, hostKey: HostKey? = null) = Profile(
        id = "", name = name, server = Server("h", 22, "u"), auth = Auth(Auth.IMPORTED, "k"),
        routes = listOf("10.0.0.0/8"), hostKey = hostKey,
    )

    @Test fun firstProfileBecomesDefaultAndSurvivesReload() = runBlocking {
        val a = repo.save(profile("A"))
        val b = repo.save(profile("B"))
        assertTrue(a.id.isNotEmpty() && a.id != b.id)
        assertEquals(a.id, repo.defaultProfile()?.id)
        repo.setDefault(b.id)
        assertEquals(b.id, repo.defaultProfile()?.id)

        // After a "restart", a new store on the same file sees the same data. DataStore allows
        // one active instance per file, so stop the first one.
        t.scope.coroutineContext[Job]!!.cancelAndJoin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val again = ProfileRepository(AppStore(File(t.dir, "store.json"), scope), scope).defaultProfile()
            assertEquals("B", again?.name)
        } finally {
            scope.cancel()
        }
    }

    @Test fun deleteMovesTheDefault() = runBlocking {
        val a = repo.save(profile("A"))
        val b = repo.save(profile("B"))
        repo.delete(a.id)
        assertEquals(b.id, repo.defaultProfile()?.id)
        repo.delete(b.id)
        assertNull(repo.defaultProfile())
    }

    @Test fun saveNeverSetsOrChangesAPin() = runBlocking {
        // A new profile can't arrive pinned…
        val p = repo.save(profile("A", HostKey(keyA.type, keyA.fingerprint)))
        assertNull(p.hostKey)
        assertNull(repo.profile(p.id)?.hostKey)
        // …and an edit can't swap the pin.
        repo.trustHostKey(p.id, keyA)
        repo.save(p.copy(name = "A2", hostKey = HostKey(keyB.type, keyB.fingerprint)))
        val stored = repo.profile(p.id)!!
        assertEquals("A2", stored.name)
        assertEquals(keyA.fingerprint, stored.hostKey?.fingerprint)
    }

    @Test fun aChangedKeyNeedsForgetFirst() = runBlocking {
        val p = repo.save(profile("A"))
        val pinned = repo.trustHostKey(p.id, keyA)
        assertEquals(HostKey(keyA.type, keyA.fingerprint, clock.toString()), pinned.hostKey)
        try {
            repo.trustHostKey(p.id, keyB)
            fail("replaced a pinned host key")
        } catch (_: HostKeyAlreadyPinnedException) {
        }
        assertEquals(keyA.fingerprint, repo.profile(p.id)?.hostKey?.fingerprint)

        repo.forgetHostKey(p.id)
        assertNull(repo.profile(p.id)?.hostKey)
        repo.trustHostKey(p.id, keyB)
        assertEquals(keyB.fingerprint, repo.profile(p.id)?.hostKey?.fingerprint)
    }
}
