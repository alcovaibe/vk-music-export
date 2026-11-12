package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class FriendListRefreshPolicyTest {
    @Test fun freshForSixHoursAndRollbackExpiresImmediately() {
        val now=1_700_000_000_000L
        assertTrue(FriendListRefreshPolicy.fresh(now,now))
        assertTrue(FriendListRefreshPolicy.fresh(now,now+FriendListRefreshPolicy.FRESH_MS-1))
        assertFalse(FriendListRefreshPolicy.fresh(now,now+FriendListRefreshPolicy.FRESH_MS))
        assertFalse(FriendListRefreshPolicy.fresh(now,now-1))
        assertFalse(FriendListRefreshPolicy.fresh(null,now))
        assertFalse(FriendListRefreshPolicy.fresh(0,now))
    }
    @Test fun retryCooldownPreventsRapidRemoteReloadButManualRefreshCanBypassIt() {
        val now=1_700_000_000_000L
        assertTrue(FriendListRefreshPolicy.retryAllowed(null,now))
        assertFalse(FriendListRefreshPolicy.retryAllowed(now,now))
        assertFalse(FriendListRefreshPolicy.retryAllowed(now,now+FriendListRefreshPolicy.RETRY_MS-1))
        assertTrue(FriendListRefreshPolicy.retryAllowed(now,now+FriendListRefreshPolicy.RETRY_MS))
        assertTrue(FriendListRefreshPolicy.retryAllowed(now,now-1))
    }
}
