package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class BackgroundTransportTest {
    @Test fun allSupportedVersionsSelectExactlyOneTransport() {
        (29..33).forEach{assertFalse("API $it must use a foreground service",BackgroundTransport.usesUserInitiatedJobs(it))}
        (34..37).forEach{assertTrue("API $it must retain user-initiated jobs",BackgroundTransport.usesUserInitiatedJobs(it))}
    }
}
