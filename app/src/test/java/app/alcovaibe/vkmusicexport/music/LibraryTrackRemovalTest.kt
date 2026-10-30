package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LibraryTrackRemovalTest {
    private val track=NativeTrack("12_34","CI","Artist",1)
    private val access=MusicAccess("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")
    private fun record(path: String=ExportLayout.ROOT+"Моя музыка/")=PublishedAudio("key",track,"content://media/external_primary/audio/media/1","CI.mp3",path,"mp3","audio/mpeg",1000,10,"a".repeat(64))
    @Test fun userSelectedReplacementNeverAuthorizesVkRemovalAfterSharingOrMoving() {
        val replacement=record().copy(source=ExportSource(12,"library","my"),catalogueVerified=false)
        assertFalse(LibraryRemovalProtocol.libraryCopy(12,track,replacement))
        assertThrows(MusicFailure::class.java){LibraryRemovalProtocol.record(12,track,replacement)}
        val moved=replacement.copy(relativePath=ExportLayout.ROOT+"Альбомы/CI/",recoveredLocation=true)
        assertFalse(LibraryRemovalProtocol.libraryCopy(12,track,moved))
        assertFalse(Json.decodeFromString<PublishedAudio>(Json.encodeToString(moved)).catalogueVerified)
        // Old manual imports without a provenance field are conservative on upgrade.
        val legacy=Json.encodeToString(record(ExportLayout.ROOT+"Восстановленные/"))
        assertFalse(legacy.contains("catalogueVerified"))
        assertFalse(Json.decodeFromString<PublishedAudio>(legacy).catalogueVerified)
    }
    @Test fun wrongAccountForeignFoldersAndMissingCopyNeverReachMutation()=runBlocking<Unit> {
        assertThrows(MusicFailure::class.java){LibraryRemovalProtocol.audioId(13,track)}
        assertThrows(MusicFailure::class.java){LibraryRemovalProtocol.audioId(12,track.copy(id="-12_34"))}
        assertThrows(MusicFailure::class.java){LibraryRemovalProtocol.record(12,track,record(ExportLayout.ROOT+"Альбомы/Release/"))}
        var calls=0
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess): NativeProbe=error("not needed")
            override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("not needed")
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("not needed")
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("not needed")
            override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack){calls++}
        }
        try{LibraryTrackRemoval(api){false}.remove(access,12,track,record());fail("missing file must block")}
        catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
        assertEquals(0,calls)
        LibraryTrackRemoval(api){true}.remove(access,12,track,record());assertEquals(1,calls)
    }
    @Test fun clientBindsExactAccountAndSendsOnePostWithoutPlaylistOrTokenInUrl()=runBlocking<Unit> {
        val requests=mutableListOf<Request>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val req=chain.request();requests.add(req)
            val body=if(req.url.encodedPath.endsWith("users.get"))"""{"response":[{"id":12}]}""" else """{"response":1}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        VkAudioClient(client){_,_->}.removeLibraryTrack(access,12,track)
        assertEquals(listOf("users.get","audio.delete"),requests.map{it.url.encodedPath.substringAfterLast('/')})
        val req=requests.last();assertEquals("POST",req.method);assertNull(req.url.query)
        val body=req.body as FormBody;val fields=(0 until body.size).associate{body.name(it) to body.value(it)}
        assertEquals(setOf("access_token","v","owner_id","audio_id"),fields.keys)
        assertEquals("12",fields["owner_id"]);assertEquals("34",fields["audio_id"])
        requests.clear()
        val wrong=NetworkClients.api().newBuilder().addInterceptor{chain->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"response":[{"id":13}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try{VkAudioClient(wrong){_,_->}.removeLibraryTrack(access,12,track);fail("account mismatch")}
        catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
        assertEquals(1,requests.size)
    }
    @Test fun accessFailureIsNotRetriedAndOnlyIntegerOneMeansConfirmedSuccess()=runBlocking<Unit> {
        val methods=mutableListOf<String>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val req=chain.request();methods.add(req.url.encodedPath.substringAfterLast('/'))
            val body=if(methods.last()=="users.get")"""{"response":[{"id":12}]}""" else """{"error":{"error_code":5}}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try{VkAudioClient(client){_,_->}.removeLibraryTrack(access,12,track);fail("must reject")}
        catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
        assertEquals(listOf("users.get","audio.delete"),methods)
        for(raw in listOf("0","true","\"1\"","null","{}"))assertThrows(MusicFailure::class.java){LibraryRemovalProtocol.success(Json.parseToJsonElement(raw))}
        LibraryRemovalProtocol.success(Json.parseToJsonElement("1"))
    }
}
