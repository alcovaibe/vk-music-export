package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import org.junit.Assert.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalCatalogDeviceTest {
    private val song=NativeTrack("12_99","Saved occurrence","Fixture",1)
    private fun list(id: Long)=NativePlaylist(12,id,"Same title",2,"SECRET_ACCESS_KEY",null,null,null,
        PlaylistKind.ALBUM,photo="https://audio.userapi.com/PRIVATE?signature=SECRET")
    private fun tasks(list: NativePlaylist,account: Long=12)=ExportLayout.playlist(NativePage(listOf(song,song),2),list,account=account)
    private fun file(task: AudioExportTask)=PublishedAudio(task.key,task.track,
        "content://media/external/audio/media/11","Fixture.mp3",ExportLayout.ROOT+"Albums/Fixture/",
        "mp3","audio/mpeg",1000,123,"0".repeat(64),source=task.source,occurrence=task.occurrence)
    private fun fixture(block:suspend(ContextWrapper,File)->Unit)=runBlocking<Unit>(Dispatchers.IO) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"local-catalog-"+UUID.randomUUID()).apply{check(mkdirs())}
        val context=object: ContextWrapper(base){override fun getNoBackupFilesDir()=root}
        try{block(context,root)}finally{root.deleteRecursively()}
    }
    @Test fun accountScopedSnapshotsRetainRemovedSourcesNamesAndRepeatedOrderAcrossReopen()=fixture{context,_->
        val album=list(8);val other=list(9)
        val first=tasks(album).map(::file);val second=tasks(other).map(::file)
        val foreign=tasks(album,13).map(::file)
        AudioExportStore(context).use{store->
            (first.reversed()+second+foreign).forEach(store::imported)
            val cache=LocalCatalogSnapshots(store)
            cache.remember(12,album,NativePage(listOf(song,song),2))
            cache.remember(12,other,NativePage(listOf(song,song),2))
            cache.remember(13,album,NativePage(listOf(song,song),2))
            store.clearQueue()
            for((_,payload)in store.checkpoints("local_catalog:")) {
                assertFalse(payload.contains("SECRET"));assertFalse(payload.contains("https://"));assertFalse(payload.contains("content://"))
            }
        }
        repeat(2){AudioExportStore(context).use{store->
            val cache=LocalCatalogSnapshots(store)
            val local=cache.read(12,NativeCatalog(emptyList(),0))
            assertEquals(setOf(album.key,other.key),local.retained)
            assertEquals(2,local.catalog.lists.size);assertTrue(local.catalog.lists.all{it.title=="Same title"&&it.accessKey==null&&it.photo==null})
            assertEquals(first,cache.playback(12,album.key)!!.rows)
            assertEquals(foreign,cache.playback(13,album.key)!!.rows)
            assertNull(cache.playback(13,other.key))
            assertEquals(setOf(album.key),cache.read(13).retained)
            assertEquals(2,PlaybackLists.library(NativeMusicState(probe=NativeProbe(12,NativePage(emptyList(),0)),
                selected=local.catalog.lists.first{it.key==album.key},page=NativePage(listOf(song,song),2),
                localCatalogKeys=local.retained,localCatalogAccount=12,localPlayback=cache.playback(12,album.key)),MusicLibrarySort.DEFAULT).size)
        }}
    }
    @Test fun currentRemoteSourceIsNotDuplicatedAndMissingMembershipRemainsPartial()=fixture{context,_->
        val album=list(8);val rows=tasks(album).map(::file)
        AudioExportStore(context).use{store->
            rows.forEach(store::imported);val cache=LocalCatalogSnapshots(store)
            cache.remember(12,album,NativePage(listOf(song,song),2))
            val online=cache.read(12,NativeCatalog(listOf(album),1))
            assertEquals(listOf(album),online.catalog.lists);assertTrue(online.retained.isEmpty())
            val hints=cache.available(12,NativeCatalog(emptyList(),0),presence={files->files.associate{it.uri to SavedAvailability.MISSING}})
            assertTrue(hints.catalog.lists.isEmpty());assertEquals(2,store.sourceFiles(ExportSource(12,"catalog",album.key)).size)
            assertEquals(0,cache.availablePlayback(12,album.key,presence={files->files.associate{it.uri to SavedAvailability.CHANGED}})!!.rows.size)
            store.forgetFile(rows.last())
            val retained=cache.read(12,NativeCatalog(emptyList(),0))
            assertEquals(2,retained.catalog.lists.single().count)
            assertEquals(1,retained.counts[album.key]);assertEquals(listOf(rows.first()),cache.playback(12,album.key)!!.rows)
            store.forgetFile(rows.first())
            assertTrue(cache.read(12).catalog.lists.isEmpty());assertNull(cache.playback(12,album.key))
        }
    }
    @Test fun malformedDescriptorNeverDeletesMembershipOrExposesAnotherAccountSnapshot()=fixture{context,root->
        val album=list(8);val row=file(tasks(album).first())
        val original=File(root,"user-original.mp3").apply{writeBytes(byteArrayOf(7,8,9))}
        AudioExportStore(context).use{store->
            store.imported(row);val cache=LocalCatalogSnapshots(store);cache.remember(12,album)
            val marker="local_catalog:12:"+album.key
            val previous=store.checkpoint(marker)
            store.writableDatabase.execSQL("CREATE TRIGGER abort_local_metadata BEFORE INSERT ON meta WHEN NEW.name LIKE 'local_catalog:%' BEGIN SELECT RAISE(ABORT,'injected metadata write'); END")
            try{cache.remember(12,NativePlaylist(12,8,"Changed",2,null,null,null,null,PlaylistKind.ALBUM));fail("Metadata write must roll back")}
            catch(_: SQLiteConstraintException){}
            assertEquals(previous,store.checkpoint(marker));assertEquals(row,store.existing(row.taskKey))
            store.writableDatabase.execSQL("DROP TRIGGER abort_local_metadata")
            store.checkpoint("local_catalog:12:"+album.key,"malformed")
            assertTrue(cache.read(13).catalog.lists.isEmpty())
            try{cache.read(12);fail("Malformed metadata must be reported")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertEquals(row,store.existing(row.taskKey));assertArrayEquals(byteArrayOf(7,8,9),original.readBytes())
        }
    }
}
