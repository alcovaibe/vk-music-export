package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlinx.serialization.json.Json

class AuditRegressionTest {
    private fun track(n: Int)=NativeTrack("12_$n","Песня $n","Исполнитель",180)
    @Test fun prependingAndReorderingDoNotChangeExistingTaskIdentities() {
        val old=ExportLayout.library(NativePage(listOf(track(1),track(2),track(3)),3),false,12)
        val next=ExportLayout.library(NativePage(listOf(track(99),track(3),track(1),track(2)),4),false,12)
        for(task in old)assertEquals(task.key,next.single{it.track.id==task.track.id}.key)
        assertNotEquals(old[0].position,next.single{it.track.id==old[0].track.id}.position)
    }
    @Test fun namesAccountsAndRepeatedOccurrencesHaveIndependentSemantics() {
        fun list(name: String,id: Long=5)=NativePlaylist(12,id,name,3,null,null,null,null,PlaylistKind.PLAYLIST)
        val page=NativePage(listOf(track(1),track(2),track(1)),3)
        val original=ExportLayout.playlist(page,list("Прежнее имя"),account=12)
        val renamed=ExportLayout.playlist(page,list("Новое имя"),account=12)
        assertEquals(original.map{it.key},renamed.map{it.key})
        assertNotEquals(original.first().key,original.last().key)
        assertNotEquals(original.first().key,ExportLayout.playlist(page,list("Прежнее имя"),account=99).first().key)
        assertNotEquals(original.first().key,ExportLayout.playlist(page,list("Прежнее имя",6),account=12).first().key)
    }
    @Test fun legacyQueueDeserializationKeepsItsPersistedKey() {
        val task=Json.decodeFromString<AudioExportTask>("""{"key":"old-position-key","track":{"id":"12_1","title":"Песня","artist":"CI","duration":1},"folders":["Моя музыка"],"position":42}""")
        assertEquals("old-position-key",task.key);assertNull(task.source);assertEquals(42,task.position)
    }
    @Test fun m3uPreservesOrderUnicodeAndRepeatedOccurrencesUsingRelativePaths() {
        val tasks=ExportLayout.playlist(NativePage(listOf(track(2),track(1),track(2)),3),
            NativePlaylist(12,5,"Подборка",3,null,null,null,null,PlaylistKind.PLAYLIST),account=12)
        val directory=ExportLayout.relative(tasks.first())
        val files=tasks.associate{it.key to PublishedAudio(it.key,it.track,"content://unused","${it.position}.mp3",directory,
            "mp3","audio/mpeg",180_000,10,"a".repeat(64))}
        val text=M3uPlaylist.render(directory,tasks.reversed(),files)
        assertTrue(text.startsWith("#EXTM3U\n"));assertFalse(text.contains("#VKME-INCOMPLETE"))
        assertTrue(text.indexOf("./1.mp3")<text.indexOf("./2.mp3"));assertTrue(text.indexOf("./2.mp3")<text.indexOf("./3.mp3"))
        assertTrue(text.contains("Исполнитель - Песня 2"))
        assertTrue(M3uPlaylist.render(directory,tasks,files-tasks[1].key).contains("#VKME-INCOMPLETE"))
    }
    private fun atom(type: String,data: ByteArray)=ByteBuffer.allocate(8+data.size).putInt(data.size+8)
        .put(type.toByteArray(Charsets.ISO_8859_1)).put(data).array()
    private fun m4a(rate: Int,config: Int): ByteArray {
        val mdhd=ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(rate).putInt(1024).putInt(0).array()
        val table=atom("stsd",byteArrayOf(config.toByte()))+atom("stts",byteArrayOf(0,0,0,1))+
            atom("stsz",byteArrayOf(0,0,0,4))+atom("stco",ByteArray(8))
        return atom("ftyp","M4A ".toByteArray())+atom("moov",atom("trak",atom("mdia",atom("mdhd",mdhd)+
            atom("minf",atom("stbl",table)))))+atom("mdat",byteArrayOf(1,2,3,4))
    }
    @Test fun identicalMdatWithDifferentDecoderConfigurationCannotMatch() {
        val directory=kotlin.io.path.createTempDirectory("decoder-signature").toFile()
        try {
            val a=File(directory,"a.m4a").apply{writeBytes(m4a(44100,4))}
            val b=File(directory,"b.m4a").apply{writeBytes(m4a(48000,3))}
            assertEquals(EmbeddedArtwork.payloadDigest(a,"m4a"),EmbeddedArtwork.payloadDigest(b,"m4a"))
            assertNotEquals(EmbeddedArtwork.audioFingerprint(a,"m4a"),EmbeddedArtwork.audioFingerprint(b,"m4a"))
            b.writeBytes(m4a(44100,3))
            assertNotEquals(EmbeddedArtwork.audioFingerprint(a,"m4a"),EmbeddedArtwork.audioFingerprint(b,"m4a"))
        }finally{directory.deleteRecursively()}
    }
    @Test fun portableM4aTextTagsDoNotChangeSampleDataOrItsInterpretation() {
        val directory=kotlin.io.path.createTempDirectory("portable-tags").toFile()
        try {
            val a=File(directory,"a.m4a").apply{writeBytes(m4a(44100,4))};val b=File(directory,"b.m4a")
            val track=track(1).copy(album=MusicAlbum("album:1","Альбом"))
            EmbeddedArtwork.writeTextMetadata(a,b,track)
            assertEquals(EmbeddedArtwork.audioFingerprint(a,"m4a"),EmbeddedArtwork.audioFingerprint(b,"m4a"))
            val data=b.readBytes().toString(Charsets.UTF_8)
            assertTrue(data.contains(track.title));assertTrue(data.contains(track.artist));assertTrue(data.contains("Альбом"))
        }finally{directory.deleteRecursively()}
    }
    @Test fun failedInitializationCanBeRetriedAndConcurrentWaitersShareTheRetry()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        try {
            val ready=RetryableReadiness(scope){if(++calls==1)throw java.io.IOException("fixture")}
            assertTrue(runCatching{ready.deferred().await()}.isFailure)
            val a=ready.deferred();val b=ready.deferred();a.await();b.await()
            assertSame(a,b);assertEquals(2,calls)
        }finally{scope.cancel()}
    }
    @Test fun cancellingAWaiterDoesNotCancelSharedRecovery()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val gate=CompletableDeferred<Unit>();var calls=0
        try {
            val ready=RetryableReadiness(scope){calls++;gate.await()}
            val first=launch{ready.deferred().await()};yield();first.cancelAndJoin()
            val attempt=ready.deferred();assertFalse(attempt.isCancelled);gate.complete(Unit);attempt.await()
            assertEquals(1,calls)
        }finally{scope.cancel()}
    }
    @Test fun newPairingCanBeVerifiedWhenOldEnvelopeIsUnreadable()=runTest {
        val store=object: MusicCredentialStore {
            override fun read(): MusicAccess?=throw java.io.IOException("corrupt envelope")
            override fun replace(expected: MusicAccess,next: MusicAccess): Boolean=error("Do not save during verification")
        }
        val manager=MusicSessionManager(store,object: MusicRenewer {
            override suspend fun renew(access: MusicAccess): MusicAccess=error("Do not renew old corrupt access")
        },{error("Unused")},io=Dispatchers.Unconfined)
        val candidate=MusicAccess("a".repeat(64),"Synthetic")
        assertSame(candidate,manager.request(candidate){it})
    }
}
