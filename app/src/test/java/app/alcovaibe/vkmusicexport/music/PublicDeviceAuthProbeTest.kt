package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public compatibility check. Never uses an account, browser profile or approval. */
class PublicDeviceAuthProbeTest {
    @Test fun currentNativeCodeCanCreateAnUnapprovedAnonymousPairingRequest()=runBlocking {
        assumeTrue(System.getenv("VKM_PUBLIC_BOOTSTRAP_PROBE")=="1")
        fun mono()=System.nanoTime()/1_000_000
        val api=VkDeviceAuthClient(NetworkClients.api(),::mono,{System.currentTimeMillis()}){type,fields->
            // The production observer contains only fixed stages, shape flags and numbers.
            println("PUBLIC_NATIVE_PROBE "+type+" "+fields)
        }
        val s=api.begin("VK Music Export / public anonymous compatibility check")
        assertTrue(s.expiresAt>mono())
        assertTrue(s.expiresAt-mono()<=300_000)
        assertTrue(DeviceAuthProtocol.browserUrl(s.url).host in setOf("qr.vk.ru","vk.com","vk.ru","id.vk.com","id.vk.ru"))
        assertTrue(api.poll(s) is DeviceCheck.Pending)
        println("PUBLIC_NATIVE_PROBE result=READY_UNAPPROVED; account_login_and_music_access=NOT_TESTED")
    }
}
