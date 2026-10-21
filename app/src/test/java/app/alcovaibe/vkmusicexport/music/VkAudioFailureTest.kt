package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class VkAudioFailureTest {
    @Test fun apiDnsFailureIncludesStageAndFixedKindWithoutCredentialOrExceptionText()=runBlocking {
        val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val c=NetworkClients.api().newBuilder().addInterceptor{throw UnknownHostException("PRIVATE_HOST_PASSWORD")}.build()
        val access=MusicAccess("PRIVATE_TOKEN_".repeat(8),"Synthetic agent")
        try{VkAudioClient(c){t,f->events.add(t to f)}.library(access);fail()}
        catch(e: MusicFailure){assertEquals(MusicError.NETWORK,e.code);assertEquals(MusicFailureKind.DNS,e.kind);assertNull(e.cause)}
        val failure=events.single{it.first=="native_music_failure"}.second
        assertEquals("identity",failure["stage"]);assertEquals("DNS",failure["kind"])
        assertFalse(events.toString().contains("PRIVATE"))
    }
    @Test fun unexpectedClientFailureIsResponseInsteadOfBlamingNetwork()=runBlocking {
        val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val c=NetworkClients.api().newBuilder().addInterceptor{throw IllegalStateException("PRIVATE_TOKEN")}.build()
        try{VkAudioClient(c){t,f->events.add(t to f)}.library(MusicAccess("a".repeat(64),"Synthetic agent"));fail()}
        catch(e: MusicFailure){assertEquals(MusicError.RESPONSE,e.code);assertEquals(MusicFailureKind.OTHER,e.kind)}
        assertEquals("RESPONSE",events.single{it.first=="native_music_failure"}.second["code"])
        assertFalse(events.toString().contains("PRIVATE"))
    }
}
