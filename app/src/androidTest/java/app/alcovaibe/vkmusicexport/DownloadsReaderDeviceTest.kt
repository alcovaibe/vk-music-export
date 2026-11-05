package app.alcovaibe.vkmusicexport

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DownloadsReaderDeviceTest {
    private suspend fun until(check:()->Boolean){withTimeout(10_000){while(!check())delay(10)}}
    @Test fun byteProgressDoesNotRefetchAndHiddenScreenStopsReading()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val open=MutableStateFlow(true);val foreground=MutableStateFlow(true)
        val versions=MutableStateFlow(ExportSnapshot(queueId="first",revision=1));val reads=AtomicInteger()
        try {
            val reader=DownloadsReader(scope,combine(open,foreground){a,b->a&&b},versions){tab,offset->reads.incrementAndGet();ExportQueuePage("first",versions.value.revision,tab,offset)}
            until{reader.state.value.page!=null};assertEquals(1,reads.get())
            repeat(100){versions.value=versions.value.copy(progress=AudioProgress("download",it.toLong()))}
            delay(100);assertEquals(1,reads.get())
            reader.select(DownloadsTab.ERRORS);until{reader.state.value.page?.tab==DownloadsTab.ERRORS};assertEquals(2,reads.get())
            open.value=false;delay(100);versions.value=versions.value.copy(revision=2);delay(100);assertEquals(2,reads.get())
            open.value=true;until{reader.state.value.page?.revision==2L};assertEquals(3,reads.get())
            foreground.value=false;delay(100);versions.value=versions.value.copy(revision=3);delay(100);assertEquals(3,reads.get())
            foreground.value=true;until{reader.state.value.page?.revision==3L};assertEquals(4,reads.get())
            reader.retry();until{reads.get()==5&&!reader.state.value.loading}
        }finally{scope.coroutineContext[Job]!!.cancelAndJoin()}
    }
    @Test fun cancelledOlderQueuePageCannotReplaceTheNewQueue()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val open=MutableStateFlow(true)
        val versions=MutableStateFlow(ExportSnapshot(queueId="old",revision=1));val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            val reader=DownloadsReader(scope,open,versions){tab,offset->
                val captured=versions.value
                if(captured.queueId=="old")withContext(NonCancellable){entered.complete(Unit);release.await()}
                ExportQueuePage(captured.queueId,captured.revision,tab,offset)
            }
            withTimeout(10_000){entered.await()};versions.value=versions.value.copy(queueId="new",revision=2)
            release.complete(Unit);until{reader.state.value.page?.queueId=="new"}
            assertEquals(2L,reader.state.value.page!!.revision)
            reader.select(DownloadsTab.DONE,50);until{reader.state.value.page?.tab==DownloadsTab.DONE}
            assertEquals("new",reader.state.value.page!!.queueId);assertEquals(50,reader.state.value.page!!.offset)
        }finally{release.complete(Unit);scope.coroutineContext[Job]!!.cancelAndJoin()}
    }
}
