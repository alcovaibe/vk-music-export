package app.alcovaibe.vkmusicexport.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.alcovaibe.vkmusicexport.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** UI commands remain on Main; the service, not an Activity, owns the only player. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackRuntime {
    const val OPEN_EXTRA="open_music_player"
    const val START="app.alcovaibe.vkmusicexport.PLAY_QUEUE"
    internal data class Request(val id: String,val selection: PlaybackSelection,val session: String?,val position: Long)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(PlaybackState());val state=mutable.asStateFlow()
    val expanded=MutableStateFlow(false)
    internal var service: MusicPlaybackService?=null
    private var pending: Request?=null
    private var generation=0
    internal fun publish(value: PlaybackState){mutable.value=value}
    internal fun take(id: String?)=pending?.takeIf{it.id==id}.also{if(it!=null)pending=null}
    fun play(context: Context,entries: List<PlaybackEntry>,start: Int=0,position: Long=0) {
        val selected=PlaybackLists.select(entries,start)?:return
        val app=context.applicationContext;val g=++generation
        expanded.value=true
        scope.launch {
            try {
                val session=if(selected.entries.any{!it.offline})withContext(Dispatchers.IO) {
                    val stored=MusicAccessStore(app).read()?:throw MusicFailure(MusicError.ACCESS)
                    musicCheck(selected.entries.filter{!it.offline}.all{it.account!=null&&(stored.accountId==null||it.account==stored.accountId)},MusicError.ACCOUNT_CHANGED)
                    stored.readSessionId
                }else null
                if(g!=generation)return@launch
                val request=Request(UUID.randomUUID().toString(),selected,session,position.coerceAtLeast(0));pending=request
                ContextCompat.startForegroundService(app,Intent(app,MusicPlaybackService::class.java).setAction(START).putExtra("request",request.id))
            }catch(e: CancellationException){throw e}catch(_: Exception) {
                if(g==generation){pending=null;mutable.update{it.copy(error="Не удалось начать воспроизведение. Проверь доступ и повтори.")}}
            }
        }
    }
    fun toggle(context: Context) {
        val current=service
        if(current==null){val s=state.value;if(s.current!=null)play(context,s.entries,s.index,s.positionMs)}
        else current.toggle()
    }
    fun next(){service?.next()}
    fun previous(){service?.previous()}
    fun seek(position: Long){service?.seek(position)}
    fun shuffle(){service?.shuffle()}
    fun repeat(){service?.repeat()}
    fun stop(){generation++;pending=null;service?.stopPlayback();expanded.value=false;mutable.value=PlaybackState()}
    fun stopOnline(){if(state.value.current?.offline==false||pending?.selection?.entries?.any{!it.offline}==true)stop()}
}

/** Foreground media playback is intentionally separate from dataSync/file-maintenance services. */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var entries: List<PlaybackEntry> = emptyList()
    private var readSession: String?=null
    private var artwork: ByteArray?=null
    private var artWork: Job?=null
    private var failure: String?=null
    private var serial=0
    private var refreshedIndex=-1
    private var loadingIndex=-1
    private var lastArtIndex=-1
    private val sessions by lazy{MusicSessions.get(this)}
    private val api by lazy{VkAudioClient(sessions=sessions)}
    private val covers by lazy{ArtworkLoader(api)}
    internal var resolveForTest: (suspend(PlaybackEntry)->Uri)?=null
    override fun onCreate() {
        super.onCreate()
        player=ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        val activity=PendingIntent.getActivity(this,6701,Intent(this,MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(PlaybackRuntime.OPEN_EXTRA,true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession=MediaSession.Builder(this,player).setSessionActivity(activity).setCallback(object: MediaSession.Callback {
            override fun onConnect(session: MediaSession,controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if(!controller.isTrusted&&controller.packageName!=packageName)return MediaSession.ConnectionResult.reject()
                val commands=MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
            }
            override fun onAddMediaItems(session: MediaSession,controller: MediaSession.ControllerInfo,mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                Futures.immediateFuture(emptyList())
        }).build()
        player.addListener(object: Player.Listener {
            override fun onEvents(player: Player,events: Player.Events){publish()}
            override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
                if(loadingIndex!=player.currentMediaItemIndex){loadingIndex=player.currentMediaItemIndex;refreshedIndex=-1;failure=null;loadCover()}
            }
            override fun onPlayerError(error: PlaybackException) {
                // Signed links may expire while paused. Resolve the exact ID once again, preserving position.
                val status=generateSequence<Throwable>(error){it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val index=player.currentMediaItemIndex
                if(entries.getOrNull(index)?.offline==false&&status in setOf(401,403,404,410)&&refreshedIndex!=index) {
                    refreshedIndex=index;val at=player.currentPosition.coerceAtLeast(0);val resume=player.playWhenReady
                    player.setMediaSources(sources(),index,at);player.prepare();player.playWhenReady=resume
                }else {failure=failure?:"Не удалось воспроизвести запись. Повтори или выбери следующий трек.";publish()}
            }
        })
        PlaybackRuntime.service=this
        scope.launch {
            var tick=0
            while(isActive) {
                publish()
                if(++tick%4==0&&entries.getOrNull(player.currentMediaItemIndex)?.offline==false) {
                    val expected=readSession
                    val valid=withContext(Dispatchers.IO){MusicAccessStore(this@MusicPlaybackService).read()?.readSessionId==expected}
                    if(!valid){player.pause();player.clearMediaItems();failure="Подключение ВК изменилось. Запусти музыку заново.";publish()}
                }
                delay(500)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo)=mediaSession
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val result=super.onStartCommand(intent,flags,startId)
        if(intent?.action==PlaybackRuntime.START) {
            val request=PlaybackRuntime.take(intent.getStringExtra("request"))
            if(request!=null)start(request)else if(entries.isEmpty())stopSelf()
        }
        return result
    }
    private fun start(request: PlaybackRuntime.Request) {
        serial++;entries=request.selection.entries;readSession=request.session;failure=null;artwork=null;loadingIndex=-1;refreshedIndex=-1
        player.setMediaSources(sources(),request.selection.start,request.position)
        player.prepare();player.play();loadCover();publish()
    }
    private fun item(index: Int,entry: PlaybackEntry)=MediaItem.Builder().setMediaId("listen:$serial:$index")
        .setUri("vkmusic://queue/$serial/$index")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.track.displayTitle).setArtist(entry.track.artist)
            .setAlbumTitle(entry.track.album?.title).setDescription(entry.sourceLabel).build()).build()
    private fun sources(): List<MediaSource> {
        val expectedSession=readSession;val run=serial
        return entries.mapIndexed {i,entry->
            PlaybackMediaSource(item(i,entry),PlaybackMediaSource.factory(this)) {
                try {
                    val uri=resolveForTest?.invoke(entry)?:if(entry.offline) {
                        withContext(Dispatchers.IO){SharedAudioStore(this@MusicPlaybackService).requireValid(checkNotNull(entry.saved))}
                        Uri.parse(checkNotNull(entry.saved).uri)
                    }else {
                        val before=sessions.current()
                        musicCheck(before.readSessionId==expectedSession,MusicError.ACCOUNT_CHANGED)
                        val account=before.accountId?:api.accountOwner(before)
                        musicCheck(account==entry.account,MusicError.ACCOUNT_CHANGED)
                        val resolved=api.resolve(before,entry.track)
                        sessions.continueRead(before)
                        Uri.parse(MediaAddress.require(resolved.url).toString())
                    }
                    currentCoroutineContext().ensureActive();uri
                }catch(e: CancellationException){throw e}catch(e: Exception) {
                    withContext(Dispatchers.Main){if(serial==run&&player.currentMediaItemIndex==i)failure=(e as? MusicFailure)?.code?.label?:"Не удалось открыть аудио."}
                    throw e
                }
            }
        }
    }
    private fun loadCover() {
        artWork?.cancel();artwork=null
        if(lastArtIndex in 0 until player.mediaItemCount&&lastArtIndex!=player.currentMediaItemIndex) {
            val old=player.getMediaItemAt(lastArtIndex)
            player.replaceMediaItem(lastArtIndex,old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon().setArtworkData(null,MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
        }
        lastArtIndex=-1
        val index=player.currentMediaItemIndex;val entry=entries.getOrNull(index)?:return;val run=serial
        artWork=scope.launch {
            val bytes=try{withContext(Dispatchers.IO) {
                if(entry.offline) {
                    val retriever=MediaMetadataRetriever()
                    try {retriever.setDataSource(this@MusicPlaybackService,Uri.parse(checkNotNull(entry.saved).uri))
                        retriever.embeddedPicture?.takeIf{it.size<=2*1024*1024}?.let{ArtworkLoader.decode(it).bytes}}
                    finally{retriever.release()}
                }else {
                    val a=sessions.current();musicCheck(a.readSessionId==readSession&&a.accountId==entry.account,MusicError.ACCOUNT_CHANGED)
                    covers.load(a,entry.track)?.bytes
                }
            }}catch(e: CancellationException){throw e}catch(_: Exception){null}
            if(run==serial&&index==player.currentMediaItemIndex) {
                artwork=bytes
                if(bytes!=null) {
                    lastArtIndex=index
                    val old=player.getMediaItemAt(index)
                    player.replaceMediaItem(index,old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon().setArtworkData(bytes,MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
                }
                publish()
            }
        }
    }
    private fun publish() {
        if(!::player.isInitialized)return
        val previous=PlaybackRuntime.state.value
        val duration=player.duration.takeIf{it!=C.TIME_UNSET&&it>0}?:entries.getOrNull(player.currentMediaItemIndex)?.track?.duration?.toLong()?.times(1000)?:0
        PlaybackRuntime.publish(previous.copy(entries=entries,index=player.currentMediaItemIndex.coerceAtLeast(0),playing=player.isPlaying,
            buffering=player.playbackState==Player.STATE_BUFFERING,positionMs=player.currentPosition.coerceAtLeast(0),durationMs=duration,
            bufferedMs=player.bufferedPosition.coerceAtLeast(0),seekable=player.isCurrentMediaItemSeekable,shuffle=player.shuffleModeEnabled,
            repeat=player.repeatMode,previous=player.hasPreviousMediaItem()||player.currentPosition>3000,next=player.hasNextMediaItem(),error=failure,artwork=artwork))
    }
    internal fun toggle() {failure=null;if(player.isPlaying||player.playWhenReady&&player.playbackState==Player.STATE_BUFFERING)player.pause()else{player.prepare();player.play()};publish()}
    internal fun playIndex(index: Int){if(index in entries.indices){failure=null;player.seekTo(index,0);player.prepare();player.play()}}
    internal fun next(){if(player.hasNextMediaItem()){failure=null;player.seekToNextMediaItem();player.prepare();player.play()}}
    internal fun previous(){failure=null;player.seekToPrevious();player.prepare();player.play()}
    internal fun seek(position: Long){if(player.isCurrentMediaItemSeekable)player.seekTo(position.coerceIn(0,player.duration.coerceAtLeast(0)))}
    internal fun shuffle(){player.shuffleModeEnabled=!player.shuffleModeEnabled;publish()}
    internal fun repeat(){player.repeatMode=when(player.repeatMode){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF};publish()}
    internal fun stopPlayback(){serial++;entries=emptyList();artWork?.cancel();player.stop();player.clearMediaItems();stopSelf()}
    override fun onDestroy() {
        scope.cancel();artWork?.cancel();mediaSession?.release();mediaSession=null;player.release()
        if(PlaybackRuntime.service===this){PlaybackRuntime.service=null;PlaybackRuntime.publish(PlaybackRuntime.state.value.copy(playing=false,buffering=false))}
        super.onDestroy()
    }
}
