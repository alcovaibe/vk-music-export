package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Main-thread commands; the service owns the player, the history owns only durable metadata. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackRuntime {
    const val OPEN_EXTRA="open_music_player"
    const val START="app.alcovaibe.vkmusicexport.PLAY_QUEUE"
    internal data class Request(val id: String,val selection: PlaybackSelection,val session: String?,val position: Long,
        val restored: PlaybackCheckpoint?=null)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(PlaybackState());val state=mutable.asStateFlow()
    private val initialized=MutableStateFlow(false);val restorationComplete=initialized.asStateFlow()
    val expanded=MutableStateFlow(false)
    internal var service: MusicPlaybackService?=null
    private var app: Context?=null
    private var history: PlaybackHistory?=null
    private var restored: PlaybackCheckpoint?=null
    private var pending: Request?=null
    private var generation=0
    internal val commandGeneration get()=generation
    fun initialize(context: Context) {
        if(app!=null)return
        val application=context.applicationContext;app=application
        history=PlaybackHistory(application,scope,onError={failed->mutable.update{it.copy(historyError=if(failed)"Не удалось сохранить состояние плеера. Прослушивание можно продолжить." else null)}})
        val g=generation
        scope.launch {
            try {
                // Even metadata-only cold restore must honor the current login boundary.
                val record=verifiedRestore()
                if(g==generation&&service?.hasQueue!=true&&pending==null&&record!=null){restored=record;mutable.value=record.pausedState()}
            }catch(e: CancellationException){throw e}catch(_: Exception) {
                if(g==generation)mutable.update{it.copy(historyError="Не удалось восстановить прошлое прослушивание. Выбери трек заново.")}
            }finally{initialized.value=true}
        }
    }
    internal fun publish(value: PlaybackState){mutable.value=value}
    internal fun take(id: String?)=pending?.takeIf{it.id==id}.also{if(it!=null)pending=null}
    internal fun checkpoint(owner: MusicPlaybackService,record: PlaybackCheckpoint) {
        if(service!==owner||pending!=null)return
        restored=record;history?.save(record)
    }
    private suspend fun verifiedRestore(): PlaybackCheckpoint? {
        val candidate=history?.restore()?:return null
        if(candidate.entries.all{it.offline})return candidate
        val application=app?:return null
        val current=withContext(Dispatchers.IO){MusicAccessStore(application).read()}
        // Verified renewal retains readSessionId; logout and independent login cannot
        // expose an old online queue even in SystemUI's metadata-only resumption path.
        return candidate.takeIf{it.accepts(current)}
    }
    internal suspend fun restoreHistory(): PlaybackCheckpoint?=verifiedRestore()
    internal suspend fun flushHistory(){history?.flush()}
    fun prepare(context: Context,start: Int=-1,build: ()->List<PlaybackEntry>) {
        initialize(context);val application=context.applicationContext;val g=++generation
        expanded.value=true
        scope.launch {
            try {
                val entries=withContext(Dispatchers.Default){build()}
                if(g==generation)play(application,entries,start)
            }catch(e: CancellationException){throw e}catch(_: Exception) {
                if(g==generation)mutable.update{it.copy(error="Не удалось подготовить очередь. Повтори выбор трека.")}
            }
        }
    }
    fun play(context: Context,entries: List<PlaybackEntry>,start: Int=-1,position: Long=0) {
        initialize(context)
        val selected=PlaybackLists.select(entries,if(start==-1)PlaybackLists.firstPlayable(entries)else start)?:return
        enqueue(context,selected,position,null)
    }
    private fun enqueue(context: Context,selected: PlaybackSelection,position: Long,record: PlaybackCheckpoint?) {
        val application=context.applicationContext;val g=++generation;expanded.value=true
        scope.launch {
            try {
                val session=if(selected.entries.any{!it.offline})withContext(Dispatchers.IO) {
                    val stored=MusicAccessStore(application).read()?:throw MusicFailure(MusicError.ACCESS)
                    musicCheck(selected.entries.filter{!it.offline}.all{it.account!=null&&(stored.accountId==null||it.account==stored.accountId)},MusicError.ACCOUNT_CHANGED)
                    musicCheck(selected.entries.filter{!it.offline}.all{it.readSessionId==null||it.readSessionId==stored.readSessionId},MusicError.ACCESS)
                    musicCheck(record==null||record.accepts(stored),MusicError.ACCOUNT_CHANGED)
                    stored.readSessionId
                }else null
                if(g!=generation)return@launch
                val request=Request(UUID.randomUUID().toString(),selected,session,position.coerceAtLeast(0),record);pending=request
                ContextCompat.startForegroundService(application,Intent(application,MusicPlaybackService::class.java).setAction(START).putExtra("request",request.id))
            }catch(e: CancellationException){throw e}catch(_: Exception) {
                if(g==generation){pending=null;mutable.update{it.copy(error="Не удалось начать воспроизведение. Проверь доступ и повтори.")}}
            }
        }
    }
    private fun resume(record: PlaybackCheckpoint){app?.let{enqueue(it,PlaybackSelection(record.entries,record.cursor.index),record.cursor.positionMs,record)}}
    fun toggle(context: Context) {
        val current=service
        if(current?.hasQueue==true)current.toggle()
        else restored?.let(::resume)
    }
    fun playIndex(index: Int) {
        if(index !in state.value.entries.indices)return
        if(service?.hasQueue==true)service?.playIndex(index)
        else restored?.let{resume(it.copy(cursor=it.cursor.copy(index=index,positionMs=0)))}
    }
    fun next(){if(service?.hasQueue==true)service?.next()else restored?.let{r->r.adjacent(1)?.let{resume(r.copy(cursor=r.cursor.copy(index=it,positionMs=0)))}}}
    fun previous(){if(service?.hasQueue==true)service?.previous()else restored?.let{r->
        val at=if(r.cursor.positionMs>3000)r.cursor.index else r.adjacent(-1)?:r.cursor.index
        resume(r.copy(cursor=r.cursor.copy(index=at,positionMs=0)))
    }}
    private fun updatePaused(record: PlaybackCheckpoint){restored=record;mutable.value=record.pausedState();history?.save(record)}
    fun seek(position: Long) {
        if(service?.hasQueue==true)service?.seek(position)
        else restored?.let{updatePaused(it.copy(cursor=it.cursor.copy(positionMs=position.coerceIn(0,state.value.durationMs.coerceAtLeast(0)))))}
    }
    fun shuffle() {
        if(service?.hasQueue==true)service?.shuffle()
        else restored?.let{record->
            val g=generation
            scope.launch {
                val order=if(!record.cursor.shuffle)withContext(Dispatchers.Default){record.entries.indices.shuffled()}else record.order
                if(g==generation&&restored===record)updatePaused(record.copy(cursor=record.cursor.copy(shuffle=!record.cursor.shuffle),order=order))
            }
        }
    }
    fun repeat(){if(service?.hasQueue==true)service?.repeat()else restored?.let{updatePaused(it.copy(cursor=it.cursor.copy(repeat=when(it.cursor.repeat){0->2;2->1;else->0})))}}
    fun stop(){generation++;pending=null;restored=null;service?.stopPlayback();expanded.value=false;mutable.value=PlaybackState();history?.save(null)}
    fun stopOnline() {
        generation++ // Also fences a cold asynchronous restore during login/logout.
        if(restored?.entries?.any{!it.offline}==true||state.value.entries.any{!it.offline}||
            pending?.selection?.entries?.any{!it.offline}==true) {
            stop()
            return
        }
        // Logout can arrive before asynchronous cold restoration has published UI
        // state. Clear a durable online record even when there is no visible queue.
        val g=generation
        scope.launch {
            try {
                val candidate=history?.restore()
                if(g==generation&&candidate?.entries?.any{!it.offline}==true)history?.save(null)
            }catch(e: CancellationException){throw e}
             catch(_: Exception){if(g==generation)mutable.update{it.copy(historyError="Не удалось очистить старое прослушивание.")}}
        }
    }
}
