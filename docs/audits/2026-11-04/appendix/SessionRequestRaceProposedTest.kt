// Proposed regression only. Not executed: the audit host cannot bootstrap Gradle.
// Place alongside MusicSessionTest when implementing a fix; no repository file was changed by this audit.
package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRequestRaceProposedTest {
    @Test fun requestMustNotSubstituteAnIndependentLoginBetweenStoreReadAndCurrent() = runTest {
        val now = 1_800_000_000_000L
        val original = MusicAccess("vk1.a." + "A".repeat(64), "Audit/1", now + 3_600_000,
            credentialId = "credential-A", persisted = true, accountId = 12, readSessionId = "session-A")
        val replacement = MusicAccess("vk1.a." + "B".repeat(64), "Audit/1", now + 3_600_000,
            credentialId = "credential-B", persisted = true, accountId = 99, readSessionId = "session-B")
        var reads = 0
        val store = object : MusicCredentialStore {
            override fun read() = if (++reads == 1) original else replacement
            override fun replace(expected: MusicAccess, next: MusicAccess): Boolean = error("Not needed")
        }
        val manager = MusicSessionManager(store,
            object : MusicRenewer { override suspend fun renew(access: MusicAccess): MusicAccess = error("Not due") },
            { error("Not due") }, { now }, io = Dispatchers.Unconfined)
        var blockSession: String? = null
        try { manager.request(original) { blockSession = it.readSessionId } }
        catch (_: MusicFailure) { /* Rejecting a changed session is correct. */ }
        assertEquals("An unrelated login must never reach the request block", null, blockSession)
    }
}
