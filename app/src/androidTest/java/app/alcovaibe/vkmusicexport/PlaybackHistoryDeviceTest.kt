package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackHistoryDeviceTest {
    private fun fixture(block:suspend(ContextWrapper,PlaybackHistoryStore,MutableList<String>)->Unit)=runBlocking<Unit>(Dispatchers.IO) {
        val real=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(real.cacheDir,"playback-history-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object: ContextWrapper(real){override fun getNoBackupFilesDir()=root}
        val writes=mutableListOf<String>()
        try{PlaybackHistoryStore(context){writes.add(it)}.use{block(context,it,writes)}}finally{root.deleteRecursively()}
    }
    private fun record(size: Int=3,id: String="queue"): PlaybackCheckpoint {
        val rows=(1..size).map {i->
            val track=NativeTrack("12_$i","Track $i","History CI",180)
            val file=PublishedAudio("key-$i",track,"content://media/external_primary/audio/media/$i","$i.mp3",ExportLayout.ROOT+"CI/","mp3","audio/mpeg",180000,1234,"a".repeat(64))
            PlaybackEntry(track,"Saved / occurrence $i",saved=file)
        }
        return PlaybackCheckpoint(id,rows,null,PlaybackCursor(minOf(1,size-1),42000,true,1),rows.indices.reversed().toList())
    }
    @Test fun queueOccurrencesPositionAndShuffleOrderSurviveClosingEveryConnection()=fixture{context,store,_->
        val initial=record();val duplicate=initial.copy(entries=initial.entries+initial.entries[1],order=listOf(3,2,0,1))
        store.replace(duplicate);store.close()
        PlaybackHistoryStore(context).use{reopened->assertEquals(duplicate,reopened.read())}
    }
    @Test fun replacementRejectsLateCursorAndConditionalClearWithoutTouchingTheNewQueue()=fixture{_,store,_->
        store.replace(record());val next=record(id="new");store.replace(next)
        assertFalse(store.update("queue",PlaybackCursor(0,100)))
        store.clear("queue");assertEquals(next,store.read())
        assertFalse(store.update("new",PlaybackCursor(100,0)));assertEquals(next,store.read())
        store.clear("new");assertNull(store.read())
    }
    @Test fun failedReplacementRollsBackEntriesOrderAndCursorTogether()=fixture{_,store,_->
        val before=record();store.replace(before)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_history BEFORE INSERT ON playback_state WHEN NEW.queue_id='new' BEGIN SELECT RAISE(ABORT,'injected'); END")
        try{store.replace(record(7,"new"));fail("Replacement must roll back")}catch(_: SQLiteConstraintException){}
        assertEquals(before,store.read())
    }
    @Test fun malformedQueueIsRejectedAndNeverDeletesTheExistingMusicDatabase()=fixture{context,store,_->
        val marker=File(context.noBackupFilesDir,"protected-audio-marker").apply{writeText("Keep music")}
        AudioExportStore(context).use{it.checkpoint("protected","original")}
        store.replace(record());store.writableDatabase.execSQL("UPDATE playback_entries SET payload='not JSON' WHERE ordinal=1")
        try{store.read();fail("Corruption must not become an empty success")}catch(_: kotlinx.serialization.SerializationException){}
        assertEquals("Keep music",marker.readText());AudioExportStore(context).use{assertEquals("original",it.checkpoint("protected"))}
    }
    @Test fun coldRestoreAcceptsRenewalRejectsIndependentLoginAndKeepsPureOfflineQueue()=fixture{context,store,_->
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var access: MusicAccess?=MusicAccess("token","agent",accountId=12,readSessionId="read",credentialId="renewed")
        val online=PlaybackEntry(NativeTrack("12_1","Song","CI",1),"Friend",12,readSessionId="read")
        val onlineRecord=PlaybackCheckpoint("online",listOf(online),"read",PlaybackCursor(0),listOf(0))
        val history=PlaybackHistory(context,scope,{},store){access}
        try {
            store.replace(onlineRecord);assertEquals(onlineRecord,history.restore())
            access=access!!.withSession(readSessionId="new login");assertNull(history.restore());assertNull(store.read())
            store.replace(onlineRecord);access=MusicAccess("token","agent",accountId=34,readSessionId="read");assertNull(history.restore())
            store.replace(onlineRecord);access=null;assertNull(history.restore())
            val offline=record();store.replace(offline);assertEquals(offline,history.restore())
        }finally{scope.coroutineContext[Job]!!.cancelAndJoin()}
    }
    @Test fun conflatedWriterFinishesWithTheLatestStopAndDoesNotResurrectAnOlderQueue()=fixture{context,store,_->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val blocked=PlaybackHistoryStore(context){if(it=="replace"){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
        val history=PlaybackHistory(context,scope,{},blocked)
        try {
            history.save(record());assertTrue(entered.await(10,TimeUnit.SECONDS))
            history.save(record(id="new"));history.save(null);release.countDown()
            withTimeout(15_000){history.flush()};assertNull(store.read())
        }finally{release.countDown();scope.coroutineContext[Job]!!.cancelAndJoin();blocked.close()}
    }
    @Test fun queuedStopFollowedByNewPlayKeepsOnlyTheNewQueueAcrossReopen()=fixture{context,store,_->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val blocked=PlaybackHistoryStore(context){if(it=="replace"){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
        val history=PlaybackHistory(context,scope,{},blocked)
        try {
            history.save(record(id="old"));assertTrue(entered.await(10,TimeUnit.SECONDS))
            history.save(null)
            val latest=record(id="new-play")
            history.save(latest);release.countDown()
            withTimeout(15_000){history.flush()}
            assertEquals(latest,store.read())
            assertEquals(latest,history.restore())
        }finally{release.countDown();scope.coroutineContext[Job]!!.cancelAndJoin();blocked.close()}
    }
    @Test fun cursorWritesStayConstantFor1004500And20000Entries()=fixture{_,store,writes->
        val results=mutableListOf<JsonObject>()
        for(size in listOf(100,4500,20000)) {
            val record=record(size);var start=SystemClock.elapsedRealtimeNanos();store.replace(record)
            val installMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            writes.clear();start=SystemClock.elapsedRealtimeNanos()
            repeat(100){i->assertTrue(store.update(record.id,record.cursor.copy(index=i%size,positionMs=i.toLong()*1000)))}
            val updateMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            assertEquals(100,writes.count{it=="cursor"});assertFalse(writes.any{it=="replace"||it=="order"})
            start=SystemClock.elapsedRealtimeNanos();val restored=store.read()!!
            val restoreMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            assertEquals(record.entries,restored.entries);assertEquals(record.order,restored.order);assertEquals(99000L,restored.cursor.positionMs)
            results.add(buildJsonObject{put("items",size);put("install_ms",installMs);put("restore_ms",restoreMs);put("updates_ms",updateMs);put("cursor_writes",100);put("queue_rewrites",0)})
        }
        android.util.Log.i("VKPlaybackBench","VK_PLAYBACK_BENCHMARK="+buildJsonObject{put("schema",1);put("api",android.os.Build.VERSION.SDK_INT);put("results",JsonArray(results))})
    }
}
