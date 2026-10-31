package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class TrackMetadataTest {
    private val track=NativeTrack("12_34","Миражи","whiteSpot",120)
    private fun response(extra: String="")="""{"owner_id":12,"id":34,"title":"Миражи","artist":"whiteSpot","duration":120$extra}"""
    @Test fun subtitlesAreOptionalValidatedAndPersistedWithoutChangingIdentity() {
        for(extra in listOf("",",\"subtitle\":null",",\"subtitle\":\"   \"")) {
            assertEquals(track,NativeProtocol.metadataRecord(Json.parseToJsonElement("[${response(extra)}]"),track.id))
        }
        val fresh=NativeProtocol.metadataRecord(Json.parseToJsonElement("[${response(",\"subtitle\":\" hehehe Cover sped up \"")}]"),track.id)!!
        assertEquals("Миражи (hehehe Cover sped up)",fresh.displayTitle)
        assertEquals("Миражи",fresh.title)
        assertEquals(fresh,Json.decodeFromString<NativeTrack>(Json.encodeToString(fresh)))
        assertEquals(track,Json.decodeFromString<NativeTrack>("""{"id":"12_34","title":"Миражи","artist":"whiteSpot","duration":120}"""))
        val folders=listOf("Моя музыка")
        assertEquals(ExportLayout.task(track,folders,1).key,ExportLayout.task(fresh,folders,1).key)
        for(value in listOf("123","[]","\"bad\\nversion\"","\""+"a".repeat(501)+"\"")) {
            assertThrows(MusicFailure::class.java){NativeProtocol.metadataRecord(Json.parseToJsonElement("[${response(",\"subtitle\":$value")}]"),track.id)}
        }
    }
    @Test fun versionIsNotDuplicatedOrLostToALongFilenameAndNeverGuessedFromNumbers() {
        val fresh=track.copy(subtitle="hehehe Cover sped up")
        assertEquals("whiteSpot — Миражи (hehehe Cover sped up).mp3",ExportLayout.name(fresh,"mp3"))
        assertEquals("Миражи (hehehe Cover sped up)",fresh.copy(title=fresh.displayTitle).displayTitle)
        assertEquals("Миражи hehehe Cover sped up",fresh.copy(title="Миражи hehehe Cover sped up").displayTitle)
        val long=ExportLayout.name(fresh.copy(title="Песня 😀".repeat(100),artist="Исполнитель".repeat(100)),"m4a")
        assertTrue(long.endsWith(" (hehehe Cover sped up).m4a"));assertTrue(long.toByteArray().size<255)
        assertEquals("Миражи (2)",track.copy(title="Миражи (2)").displayTitle)
        assertNotEquals(DuplicateNames.key(track),DuplicateNames.key(fresh))
    }
    @Test fun exactMetadataLookupNeedsNeitherLibraryMembershipNorMediaOrArtworkAndRejectsWrongIds()=runBlocking {
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            assertEquals("/method/audio.getById",chain.request().url.encodedPath)
            val form=chain.request().body as FormBody
            assertEquals(track.id,(0 until form.size).associate{form.name(it) to form.value(it)}["audios"])
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("{\"response\":[${response(",\"subtitle\":\"slowed\"") }]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result=VkAudioClient(client,observe={_,_->}).metadata(MusicAccess("a".repeat(64),"CI/1"),track.id)!!
        assertEquals("Миражи (slowed)",result.displayTitle)
        assertNull(NativeProtocol.metadataRecord(Json.parseToJsonElement("[]"),track.id))
        assertThrows(MusicFailure::class.java){NativeProtocol.metadataRecord(Json.parseToJsonElement("[${response()}]"),"12_35")}
        assertThrows(MusicFailure::class.java){TrackMetadata.merge(track,result.copy(id="12_35"))}
        assertThrows(MusicFailure::class.java){TrackMetadata.merge(track,result.copy(duration=119))}
        assertEquals(track.copy(subtitle="slowed"),TrackMetadata.merge(track,result.copy(artist="Renamed artist")))
    }
    @Test fun playlistUsesVerifiedSavedVersionEvenWhenTheQueuedSnapshotHadNoSubtitle() {
        val task=ExportLayout.task(track,listOf("Моя музыка"),1)
        val row=PublishedAudio(task.key,track.copy(subtitle="sped up"),"content://media/external_primary/audio/media/1",
            "Миражи (sped up).mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",120000,1,"a".repeat(64))
        val text=M3uPlaylist.render(row.relativePath,listOf(task),mapOf(task.key to row))
        assertTrue(text.contains("whiteSpot - Миражи (sped up)\n"))
        assertTrue(text.contains("./Миражи (sped up).mp3"))
    }
    @Test fun mp3RewritePreservesCoverUnknownFramesAndAudioForBothId3Versions() {
        val dir=Files.createTempDirectory("metadata-test").toFile()
        try {
            for(version in listOf(3,4)) {
                fun sync(n: Int)=byteArrayOf((n ushr 21).toByte(),((n ushr 14) and 127).toByte(),((n ushr 7) and 127).toByte(),(n and 127).toByte())
                fun frame(id: String,bytes: ByteArray)=id.toByteArray()+
                    (if(version==4)sync(bytes.size)else ByteBuffer.allocate(4).putInt(bytes.size).array())+byteArrayOf(0,0)+bytes
                val cover=frame("APIC",ByteArray(130){7})
                val custom=frame("TXXX",byteArrayOf(3)+"KEEP".toByteArray())
                val body=frame("TIT2",byteArrayOf(3)+"Old title".toByteArray())+cover+custom+ByteArray(16)
                val input=File(dir,"old.mp3").apply{writeBytes(byteArrayOf(73,68,51,version.toByte(),0,0)+sync(body.size)+body+ByteArray(512){11})}
                val output=File(dir,"new.mp3")
                EmbeddedArtwork.writeTextMetadata(input,output,track.copy(subtitle="sped up"),"mp3")
                assertEquals(version,output.readBytes()[3].toInt())
                assertEquals(EmbeddedArtwork.audioFingerprint(input,"mp3"),EmbeddedArtwork.audioFingerprint(output,"mp3"))
                assertTrue(EmbeddedArtwork.hasCover(output,"mp3"))
                val raw=output.readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(raw.contains(cover.toString(Charsets.ISO_8859_1)))
                assertTrue(raw.contains(custom.toString(Charsets.ISO_8859_1)))
                assertTrue(raw.contains(track.copy(subtitle="sped up").displayTitle.toByteArray(Charsets.UTF_16).toString(Charsets.ISO_8859_1)))
                assertFalse(raw.contains("Old title"))
            }
        }finally{dir.deleteRecursively()}
    }
}
