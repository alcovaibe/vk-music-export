package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AuditRegressionDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private val json=Json{encodeDefaults=true}
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val root=File(app.cacheDir,"audit-$name").apply{deleteRecursively();mkdirs()}
        return object: ContextWrapper(app) {
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply{mkdirs()}
        } to root
    }
    private fun fixture(root: File,name: String="tone.mp3")=File(root,name).apply {
        instrumentation.context.assets.open("native-audio/$name").use{writeBytes(it.readBytes())}
    }
    private fun task(id: Int=1,owner: Long=12,position: Int=1)=ExportLayout.task(
        NativeTrack("12_${98000+id}","Аудит $id","Исполнитель",1),listOf("Плейлисты","CI аудит"),
        position,ExportSource(owner,"catalog","playlist:12:98"))
    private fun erase(store: AudioExportStore) {
        store.savedFiles().forEach{runCatching{app.contentResolver.delete(Uri.parse(it.uri),null,null)}}
        store.readableDatabase.rawQuery("SELECT value FROM meta WHERE name LIKE 'm3u:%' OR name LIKE 'm3u-pending:%'",null).use{c->
            while(c.moveToNext())runCatching {
                val uri=json.parseToJsonElement(c.getString(0)).jsonObject.getValue("uri").jsonPrimitive.content
                app.contentResolver.delete(Uri.parse(uri),null,null)
            }
        }
    }

    @Test fun realM4aHasPortableTextTagsAndStillDecodesAfterRewrite()=runBlocking<Unit>(Dispatchers.IO) {
        val (_,root)=isolated("m4a-tags")
        try {
            val t=task().track.copy(album=MusicAlbum("album:98","Проверенный альбом"))
            val out=File(root,"tagged.m4a")
            val result=AndroidAudioProcessor().process(fixture(root,"tone-aac.ts"),out,t,1.2)
            assertEquals("m4a",result.extension)
            MediaMetadataRetriever().use{reader->
                reader.setDataSource(out.path)
                assertEquals(t.title,reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals(t.artist,reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                assertEquals(t.album!!.title,reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>1000)
            }
            val again=File(root,"retagged.m4a")
            EmbeddedArtwork.writeTextMetadata(out,again,t.copy(title="Другое название")){}
            assertEquals(EmbeddedArtwork.audioFingerprint(out,"m4a"),EmbeddedArtwork.audioFingerprint(again,"m4a"))
        }finally{root.deleteRecursively()}
    }

    @Test fun shortCompleteManifestCannotPublishCataloguePreview()=runBlocking<Unit>(Dispatchers.IO) {
        val (_,root)=isolated("preview")
        try {
            try {AndroidAudioProcessor().process(fixture(root,"tone-aac.ts"),File(root,"preview.m4a"),task().track.copy(duration=180),1.2)
                fail("A complete short manifest is not a complete catalogue track")
            }catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
        }finally{root.deleteRecursively()}
    }

    @Test fun legacyArchiveMigrationIsIdempotentAndNeverDeletesOriginalOrIndex()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,root)=isolated("legacy");val store=AudioExportStore(context)
        try {
            val file=File(context.filesDir,"native-audio/old.mp3").apply{parentFile.mkdirs();writeBytes(fixture(root).readBytes())}
            val index=File(context.filesDir,"native-audio-index.json")
            index.writeText(buildJsonArray{add(buildJsonObject {
                put("fileName",file.name);put("track",json.encodeToJsonElement(task().track));put("extension","mp3")
                put("mime","audio/mpeg");put("durationMs",1200);put("bytes",file.length());put("sha256",fileSha256(file))
            })}.toString())
            val original=fileSha256(file);val indexOriginal=index.readBytes()
            repeat(2){LegacyArchiveMigration(context,store,SharedAudioStore(context)).migrate()}
            assertEquals(1,store.savedFiles().size);assertTrue(SharedAudioStore(context).valid(store.savedFiles().single()))
            assertEquals(original,fileSha256(file));assertArrayEquals(indexOriginal,index.readBytes())
            assertEquals(1,LegacyArchiveRuntime.state.value.migrated)
        }finally{erase(store);store.close();root.deleteRecursively()}
    }

    @Test fun corruptLegacyIndexKeepsAudioAndExposesRecoveryCopy()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,root)=isolated("legacy-corrupt");val store=AudioExportStore(context)
        try {
            val file=File(context.filesDir,"native-audio/old.mp3").apply{parentFile.mkdirs();writeBytes(fixture(root).readBytes())}
            val index=File(context.filesDir,"native-audio-index.json").apply{writeText("{broken")}
            val before=fileSha256(file)
            LegacyArchiveMigration(context,store,SharedAudioStore(context)).migrate()
            assertTrue(store.savedFiles().isEmpty());assertEquals(before,fileSha256(file));assertEquals("{broken",index.readText())
            assertEquals("old.mp3",LegacyArchiveRuntime.state.value.files.single().name)
            assertNotNull(LegacyArchiveRuntime.state.value.message)
        }finally{store.close();root.deleteRecursively()}
    }

    @Test fun renameAndReorderKeepSamePublishedUriAndDirectory()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,root)=isolated("stable-identity");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        try {
            val before=task();store.replace(listOf(before),account=12)
            val path=store.directory(before)
            val row=shared.publish(before,TransferResult(fixture(root),ProcessedAudio("mp3","audio/mpeg",1200)),path){}
            store.done(before,row)
            val after=before.copy(position=9,folders=listOf("Плейлисты","Переименованный"),track=before.track.copy(title="Новое название"))
            store.replace(listOf(after),account=12)
            assertEquals(path,store.directory(after));assertEquals(row.uri,store.reusable(after).single().uri)
            val noApi=object: MusicApi {
                override suspend fun library(access: MusicAccess): NativeProbe=error("No account-B request allowed")
                override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No account-B request allowed")
                override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No account-B request allowed")
                override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No account-B request allowed")
            }
            store.status("QUEUED")
            AudioExportEngine(store,shared,File(root,"transfer"),noApi,credential={error("No credential read allowed")},
                observe={_,_->},accountGuard={throw MusicFailure(MusicError.ACCOUNT_CHANGED)}).run{}
            assertEquals("PAUSED",store.snapshot().status);assertEquals(MusicError.ACCOUNT_CHANGED,store.snapshot().error)
            assertEquals(1,store.snapshot().pending);assertEquals(0,store.snapshot().done)
            val adopted=store.adopt(after,row);store.done(after,adopted)
            assertEquals(row.uri,store.savedFiles().single().uri);assertTrue(shared.valid(adopted))
        }finally{erase(store);store.close();root.deleteRecursively()}
    }

    @Test fun allFailuresAndSourceRetrySurviveDatabaseReopenWithoutResettingDone()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,root)=isolated("failures");var store=AudioExportStore(context)
        try {
            val tasks=(1..21).map{task(it,position=it)};val source=tasks.first().source!!
            val issue=ExportSourceFailure(source,"Каталог",MusicError.INCOMPLETE,PlaylistKind.PLAYLIST)
            store.replace(tasks,account=12,sources=listOf(issue))
            val row=SharedAudioStore(context).publish(tasks[0],TransferResult(fixture(root),ProcessedAudio("mp3","audio/mpeg",1200)),store.directory(tasks[0])){}
            store.done(tasks[0],row);tasks.drop(1).forEach{store.failed(it,MusicError.NETWORK)}
            store.close();store=AudioExportStore(context)
            assertEquals(20,store.snapshot().failures.size);assertEquals(listOf(issue),store.sourceFailures())
            assertEquals(12L,store.account());store.retrySource(source,tasks)
            assertEquals(1,store.snapshot().done);assertEquals(20,store.snapshot().pending)
            assertTrue(store.sourceFailures().isEmpty());assertEquals(row.uri,store.existing(tasks[0].key)!!.uri)
            try{store.bindAccount(13);fail("Cross-account binding must fail")}catch(_: MusicFailure){}
            store.clearQueue();store.replace(emptyList(),account=12,sources=listOf(issue))
            assertEquals("FINISHED_WITH_ERRORS",store.snapshot().status);assertEquals(1,store.snapshot().sourceIssues)
        }finally{erase(store);store.close();root.deleteRecursively()}
    }

    @Test fun playlistPublishesPortableOrderAndIsIdempotent()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,root)=isolated("playlist");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        try {
            val tasks=(1..3).map{task(it,position=4-it)};store.replace(tasks,account=12)
            val input=fixture(root)
            tasks.take(2).forEach{t->store.done(t,shared.publish(t,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200)),store.directory(t)){})}
            val publisher=SharedPlaylistStore(context,store);publisher.publishAll()
            val key="m3u:"+tasks.first().source!!.key
            val first=store.checkpoint(key)!!
            publisher.publishAll();assertEquals(first,store.checkpoint(key))
            val uri=Uri.parse(json.parseToJsonElement(first).jsonObject.getValue("uri").jsonPrimitive.content)
            val text=context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use{it.readText()}
            assertTrue(text.startsWith("#EXTM3U"));assertTrue(text.contains("INCOMPLETE"))
            assertTrue(text.indexOf("Аудит 2")<text.indexOf("Аудит 1"));assertFalse(text.contains("content://"))
            assertEquals(2,text.lineSequence().count{it.startsWith("./")})
            context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use{it.moveToFirst();assertEquals(0,it.getInt(0))}
        }finally{erase(store);store.close();root.deleteRecursively()}
    }

    @Test fun schemaTwoPreservesPreparedFilesAndQueueWhileAddingSourceFailures()=runBlocking<Unit>(Dispatchers.IO) {
        val (context,root)=isolated("schema-two");var store=AudioExportStore(context)
        try {
            val t=task();store.replace(listOf(t),account=12);store.status("PAUSED")
            val prepared=SharedAudioStore(context).publish(t,TransferResult(fixture(root),ProcessedAudio("mp3","audio/mpeg",1200)),store.directory(t)){store.prepared(t,it)}
            store.checkpoint("artwork-transaction","preserve-me")
            store.writableDatabase.execSQL("DROP TABLE source_failures");store.writableDatabase.version=2
            store.close();store=AudioExportStore(context)
            assertEquals(3,store.readableDatabase.version);assertEquals(t,store.taskRows().single().first)
            assertEquals("PAUSED",store.snapshot().status);assertEquals("preserve-me",store.checkpoint("artwork-transaction"))
            assertTrue(store.sourceFailures().isEmpty());assertEquals(12L,store.account())
            assertEquals(prepared,store.prepared().single().second)
            assertTrue(SharedAudioStore(context).valid(prepared))
        }finally{erase(store);store.close();root.deleteRecursively()}
    }
}
