package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Scripted vault reads reproduce replacement at exact resolution boundaries without sleeps. */
class MusicSessionBoundaryTest {
    private val now=1_800_000_000_000L
    private val initial=access("original","session-A",12)
    private fun access(id: String,session: String,owner: Long,token: String=id)=MusicAccess(
        "vk1.a."+token.repeat(64),"BoundaryTest/1",now+3_600_000,
        WebRenewal("vk1.a."+"parent".repeat(32),owner,emptyList()),
        credentialId=id,persisted=true,accountId=owner,readSessionId=session)
    private class Reads(private val values: List<MusicAccess?>): MusicCredentialStore {
        var reads=0
        override fun read(): MusicAccess?=values[minOf(reads++,values.lastIndex)]
        override fun replace(expected: MusicAccess,next: MusicAccess): Boolean=error("Foreign state must not be changed")
    }
    private fun manager(store: MusicCredentialStore)=MusicSessionManager(store,
        object: MusicRenewer {override suspend fun renew(access: MusicAccess): MusicAccess=error("Not due")},
        {error("No verification required")},{now},io=Dispatchers.Unconfined)

    @Test fun independentLoginBetweenInitialCheckAndResolutionNeverReachesRequestBlock()=runTest {
        val replacements=listOf(
            access("other","session-B",99),
            access("same-account-login","session-B",12),
            access("same-token-login","session-B",12,token="original"),
            access("original","session-B",12),
            access("original","session-A",99)
        )
        for(replacement in replacements) {
            val store=Reads(listOf(initial,replacement));var called=false
            try {
                manager(store).request(initial){called=true}
                fail("Independent login must invalidate the request")
            }catch(e: MusicFailure){assertTrue(e is MusicReadChanged)}
            assertFalse(called);assertEquals(2,store.reads)
        }
    }

    @Test fun signOutBetweenInitialCheckAndResolutionDoesNotCallRequestBlock()=runTest {
        val store=Reads(listOf(initial,null));var called=false
        try {manager(store).request(initial){called=true};fail("Signed out")}
        catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
        assertFalse(called)
    }

    @Test fun alreadyReplacedLoginWithTheSameTokenIsRejectedBeforeResolution()=runTest {
        val store=Reads(listOf(access("manual-login","session-B",12,token="original")))
        var called=false
        try {manager(store).request(initial){called=true};fail("Old session must be rejected")}
        catch(e: MusicFailure){assertTrue(e is MusicReadChanged)}
        assertFalse(called);assertEquals(1,store.reads)
    }

    @Test fun continuationAlsoRejectsChangedAccountWithReusedSessionAndCredentialIds()=runTest {
        val store=Reads(listOf(initial,access("original","session-A",99)))
        try {manager(store).continueRead(initial);fail("Owner changed")}
        catch(e: MusicFailure){assertTrue(e is MusicReadChanged)}
    }

    @Test fun verifiedRotationOfTheSameReadSessionCanReachRequestBlock()=runTest {
        val rotated=access("rotated","session-A",12)
        val store=Reads(listOf(initial,rotated))
        assertSame(rotated,manager(store).request(initial){it})
    }

    @Test fun replacementDuringRecursiveAccessRetryCannotSupplyAnotherAccountsToken()=runTest {
        val rotated=access("rotated","session-A",12)
        val replacement=access("other","session-B",99)
        val store=Reads(listOf(initial,initial,rotated,replacement))
        val seen=mutableListOf<String>()
        try {
            manager(store).request(initial) {
                seen.add(it.credentialId)
                throw MusicFailure(MusicError.ACCESS,5)
            }
            fail("Replacement must stop the retry")
        }catch(e: MusicFailure){assertTrue(e is MusicReadChanged)}
        assertEquals(listOf(initial.credentialId),seen);assertEquals(4,store.reads)
    }

    @Test fun verifiedRotationDuringRecursiveRetryStillRetriesExactlyOnce()=runTest {
        val rotated=access("rotated","session-A",12)
        val store=Reads(listOf(initial,initial,rotated,rotated))
        val seen=mutableListOf<String>()
        val result=manager(store).request(initial) {
            seen.add(it.credentialId)
            if(seen.size==1)throw MusicFailure(MusicError.ACCESS,5)
            it.token
        }
        assertEquals(rotated.token,result)
        assertEquals(listOf(initial.credentialId,rotated.credentialId),seen)
    }

    @Test fun unsavedPairingCandidateNeverUsesThePreviouslySavedAccount()=runTest {
        val candidate=MusicAccess("vk1.a."+"candidate".repeat(64),"BoundaryTest/1")
        val store=Reads(listOf(initial))
        assertSame(candidate,manager(store).request(candidate){it})
        assertEquals(1,store.reads)
    }
}
