package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Latest desired state is conflated, writes are serialized, and only queue replacement writes all items. */
internal class PlaybackHistory(context: Context,scope: CoroutineScope,private val onError:(Boolean)->Unit,
    private val store: PlaybackHistoryStore=PlaybackHistoryStore(context),
    private val readAccess: ()->MusicAccess?={MusicAccessStore(context).read()}) {
    private data class Write(val revision: Long,val record: PlaybackCheckpoint?)
    private val mutex=Mutex()
    private val desired=MutableStateFlow<Write?>(null)
    private val completed=MutableStateFlow(0L)
    private var revision=0L
    init {
        scope.launch(Dispatchers.IO) {
            var installed: String?=null;var lastOrder: List<Int>?=null
            desired.filterNotNull().collect{write->
                try {
                    mutex.withLock {
                        val record=write.record
                        if(record==null){store.clear();installed=null;lastOrder=null}
                        else if(installed!=record.id){store.replace(record);installed=record.id;lastOrder=record.order}
                        else {
                            val changedOrder=record.order.takeIf{it!==lastOrder&&it!=lastOrder}
                            check(store.update(record.id,record.cursor,changedOrder));lastOrder=record.order
                        }
                    }
                    withContext(Dispatchers.Main.immediate){onError(false)}
                }catch(e: CancellationException){throw e}catch(_: Exception) {
                    installed=null
                    withContext(Dispatchers.Main.immediate){onError(true)}
                }finally{completed.value=write.revision}
            }
        }
    }
    /** Main-thread submissions only. No SQLite, JSON or hashing on the caller. */
    fun save(record: PlaybackCheckpoint?){desired.value=Write(++revision,record)}
    suspend fun flush(){val target=desired.value?.revision?:0;completed.first{it>=target}}
    suspend fun restore(): PlaybackCheckpoint?=withContext(Dispatchers.IO) {
        flush()
        mutex.withLock {
            val record=store.read()?:return@withLock null
            val access=if(record.entries.any{!it.offline})readAccess()else null
            if(record.accepts(access))record else {store.clear(record.id);null}
        }
    }
}
