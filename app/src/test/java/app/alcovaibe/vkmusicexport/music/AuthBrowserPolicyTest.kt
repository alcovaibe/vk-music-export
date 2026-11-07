package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class AuthBrowserPolicyTest {
    @Test fun differentDefaultBrowserBrandsUseTheSamePolicy() {
        for(name in listOf("com.android.chrome","org.mozilla.firefox","com.microsoft.emmx")) {
            assertEquals(name,AuthBrowserPolicy.select(name,listOf(AuthBrowserCandidate(name,true,true,true))))
        }
    }
    @Test fun missingDefaultServiceDisabledOrUnexportedHandlerNeverReceivesLogin() {
        val good=AuthBrowserCandidate("browser",true,true,true)
        assertNull(AuthBrowserPolicy.select(null,listOf(good)))
        assertNull(AuthBrowserPolicy.select("deep.link.handler",listOf(good)))
        for(candidate in listOf(good.copy(enabled=false),good.copy(exported=false),good.copy(customTabs=false)))
            assertNull(AuthBrowserPolicy.select("browser",listOf(candidate)))
        assertNull(AuthBrowserPolicy.select("browser",emptyList()))
    }
}
