package app.alcovaibe.vkmusicexport.music

import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.PlaybackPendingIntentBuilder
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList
import app.alcovaibe.vkmusicexport.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Foreground media playback is intentionally separate from dataSync/file-maintenance services. */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    // A delayed SystemUI removal looks up the controller by notification key.
    // A new service must not reuse the previous service's key or PendingIntents.
    private val notificationSessionId=UUID.randomUUID().toString()
    private var notificationId=nextNotificationId()
    private val retiredNotificationIds=mutableSetOf<Int>()
    private var destroyed=false
    private var keepIdleNotification=false
    private companion object {
        const val NOTIFICATION_STOP="app.alcovaibe.vkmusicexport.STOP_PLAYBACK_NOTIFICATION"
        // Separate from the app's fixed job IDs and Media3's shutdown notification.
        // Randomize the initial namespace to avoid immediate reuse after process death.
        val notificationIds=AtomicInteger(kotlin.random.Random.nextInt(1 shl 30))
        fun nextNotificationId()=0x40000000 or (notificationIds.incrementAndGet() and 0x3fffffff)
    }
    private fun trace(event: () -> String) = PlaybackCommandDiagnostics.record {
        "${SystemClock.elapsedRealtime()}:service=${System.identityHashCode(this)}:queue=$serial:${event()}"
    }
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var entries: List<PlaybackEntry> = emptyList()
    internal val hasQueue get()=entries.isNotEmpty()
    private var readSession: String?=null
    private var artwork: ByteArray?=null
    private var artWork: Job?=null
    private var failure: String?=null
    private var serial=0
    private var refreshedIndex=-1
    private var loadingIndex=-1
    private var lastArtIndex=-1
    private var queueOrder: List<Int> = emptyList()
    private var queueId: String?=null
    private var savedOrder: List<Int> = emptyList()
    private var pendingOrder: List<Int>?=null
    private var suppressCheckpoint=false
    private var lastCursor: PlaybackCursor?=null
    private var lastSavedOrder: List<Int>?=null
    private var lastCheckpointAt=0L
    private val sessions by lazy{MusicSessions.get(this)}
    private val api by lazy{VkAudioClient(sessions=sessions)}
    private val covers by lazy{ArtworkLoader(api)}
    private val onlineSourceFactory by lazy{PlaybackMediaSource.factory(this)}
    private val offlineSourceFactory by lazy{PlaybackMediaSource.factory(this,offline=true)}
    override fun onCreate() {
        super.onCreate()
        val notificationProvider=DefaultMediaNotificationProvider.Builder(this).setNotificationIdProvider{notificationId}.build()
        setMediaNotificationProvider(object: MediaNotification.Provider by notificationProvider {
            override fun createNotification(session: MediaSession,buttons: ImmutableList<CommandButton>,
                actions: MediaNotification.ActionFactory,callback: MediaNotification.Provider.Callback): MediaNotification {
                val generation=notificationId
                val notification=notificationProvider.createNotification(session,buttons,actions) {changed->
                    if(!destroyed&&notificationId==generation)callback.onNotificationChanged(scopedNotification(changed,generation))
                }
                return scopedNotification(notification,generation)
            }
        })
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        trace{"notification-generation:id=$notificationId,session=$notificationSessionId"}
        PlaybackRuntime.initialize(this)
        val delegate=PlaybackMediaSource.factory(this)
        val factory=object: MediaSource.Factory by delegate {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val prefix="listen:$serial:"
                val index=mediaItem.mediaId.takeIf{it.startsWith(prefix)}?.removePrefix(prefix)?.toIntOrNull()
                musicCheck(index!=null&&index in entries.indices&&mediaItem.localConfiguration?.uri==Uri.parse("vkmusic://queue/$serial/$index"),MusicError.RESPONSE)
                val selected=checkNotNull(index)
                return source(selected,entries[selected])
            }
        }
        val core=ExoPlayer.Builder(this).setMediaSourceFactory(factory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        player=object: ExoPlayer by core {
            override fun stop() {
                trace {
                    val controller=mediaSession?.controllerForCurrentRequest
                    "stop:caller=${controller?.packageName},uid=${controller?.uid},version=${controller?.controllerVersion},notification=${controller?.let{mediaSession?.isMediaNotificationController(it)}}:state=${core.playbackState},index=${core.currentMediaItemIndex}:"+
                        Throwable().stackTrace.take(9).joinToString(" > "){"${it.className}.${it.methodName}:${it.lineNumber}"}
                }
                core.stop()
            }
            override fun prepare(){
                if(core.playbackState==Player.STATE_IDLE) {
                    val previous=notificationId;notificationId=nextNotificationId();retiredNotificationIds.add(previous)
                    getSystemService(NotificationManager::class.java).cancel(previous)
                    // The notification controller may still report the previous IDLE state.
                    // It must not cancel the fresh notification while this preparation starts.
                    updateIdleNotificationMode(true)
                    trace{"notification-generation:id=$notificationId,previous=$previous"}
                }
                trace{"prepare:state=${core.playbackState},index=${core.currentMediaItemIndex}"};core.prepare()
            }
            override fun seekTo(mediaItemIndex: Int,positionMs: Long){trace{"seek:index=$mediaItemIndex,state=${core.playbackState}"};core.seekTo(mediaItemIndex,positionMs)}
        }
        val activity=PendingIntent.getActivity(this,6701,Intent(this,MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(PlaybackRuntime.OPEN_EXTRA,true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession=MediaSession.Builder(this,player).setId(notificationSessionId).setSessionActivity(activity).setCallback(object: MediaSession.Callback {
            override fun onConnect(session: MediaSession,controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                trace{"connect:caller=${controller.packageName},uid=${controller.uid},version=${controller.controllerVersion},notification=${session.isMediaNotificationController(controller)}"}
                if(!controller.isTrusted&&controller.packageName!=packageName)return MediaSession.ConnectionResult.reject()
                val commands=MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
            }
            override fun onMediaButtonEvent(session: MediaSession,controllerInfo: MediaSession.ControllerInfo,intent: Intent): Boolean {
                trace { "button:caller=${controllerInfo.packageName},uid=${controllerInfo.uid}:"+buttonDetails(intent) }
                return false
            }
            override fun onAddMediaItems(session: MediaSession,controller: MediaSession.ControllerInfo,mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                Futures.immediateFuture(emptyList())
            override fun onPlaybackResumption(session: MediaSession,controller: MediaSession.ControllerInfo,isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                resumeFromHistory(isForPlayback)
        }).build().also{addSession(it)}
        player.addListener(object: Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int){
                updateIdleNotificationMode(player.playbackState!=Player.STATE_IDLE)
                trace{"state=$playbackState,index=${player.currentMediaItemIndex},ready=${player.playWhenReady}"}
            }
            override fun onEvents(player: Player,events: Player.Events){publish(
                events.contains(Player.EVENT_TIMELINE_CHANGED)||events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED),
                events.contains(Player.EVENT_POSITION_DISCONTINUITY)||events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)||events.contains(Player.EVENT_IS_PLAYING_CHANGED))}
            override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
                if(loadingIndex!=player.currentMediaItemIndex){loadingIndex=player.currentMediaItemIndex;refreshedIndex=-1;failure=null;loadCover()}
            }
            override fun onPlayerError(error: PlaybackException) {
                // Signed links may expire while paused. Resolve the exact ID once again, preserving position.
                val status=generateSequence<Throwable>(error){it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val index=player.currentMediaItemIndex
                if(entries.getOrNull(index)?.offline==false&&status in setOf(401,403,404,410)&&refreshedIndex!=index) {
                    refreshedIndex=index;val at=player.currentPosition.coerceAtLeast(0);val resume=player.playWhenReady
                    suppressCheckpoint=true;pendingOrder=savedOrder
                    player.setMediaSources(sources(),index,at);player.prepare();player.playWhenReady=resume;publish(refreshOrder=true,forceCheckpoint=true)
                }else {failure=failure?:"Не удалось воспроизвести запись. Повтори или выбери следующий трек.";publish()}
            }
        })
        PlaybackRuntime.service=this
        scope.launch {
            MaintenanceRuntime.status.collect {status->
                if(status.busy&&entries.getOrNull(player.currentMediaItemIndex)?.offline==true&&player.playWhenReady) {
                    player.pause();player.stop();failure="Обработка файлов приостановила прослушивание. После неё обнови сохранённое и запусти трек снова.";publish()
                }
            }
        }
        scope.launch {
            var tick=0
            while(isActive) {
                publish()
                if(++tick%4==0&&entries.getOrNull(player.currentMediaItemIndex)?.offline==false) {
                    val expected=readSession
                    val valid=withContext(Dispatchers.IO){runCatching{MusicAccessStore(this@MusicPlaybackService).read()?.readSessionId==expected}.getOrDefault(false)}
                    if(!valid){PlaybackRuntime.stopOnline();PlaybackRuntime.publish(PlaybackRuntime.state.value.copy(error="Подключение ВК изменилось. Запусти музыку заново."))}
                }
                delay(500)
            }
        }
    }
    override fun onUpdateNotificationAsync(session: MediaSession,startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        val update=super.onUpdateNotificationAsync(session,startInForegroundRequired)
        if(retiredNotificationIds.isNotEmpty())update.addListener({
            if(!destroyed&&retiredNotificationIds.isNotEmpty()) {
                // An initial cancel may precede Media3 moving foreground ownership
                // to the new ID. Retry after that update, without a timer or sleep.
                val manager=getSystemService(NotificationManager::class.java)
                retiredNotificationIds.forEach(manager::cancel)
                retiredNotificationIds.retainAll(manager.activeNotifications.map{it.id}.toSet())
            }
        },androidx.core.content.ContextCompat.getMainExecutor(this))
        return update
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo)=mediaSession
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        trace { "intent:action=${intent?.action},id=$startId:"+buttonDetails(intent) }
        if(intent?.action==NOTIFICATION_STOP) {
            if(intent.data==notificationStopUri(notificationId)&&entries.isNotEmpty()) {
                // Apply this addressed user command on the main thread now. Routing it
                // through a second asynchronous controller queue could overtake a new Play.
                trace{"notification-stop:id=$notificationId"}
                player.pause();player.stop();publish(forceCheckpoint=true)
            }else {
                trace{"ignored-old-stop:target=${intent.data}"}
                if(entries.isEmpty())stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if(isStaleNotificationStop(intent)) {
            trace{"ignored-old-stop:target=${intent?.data}"}
            if(entries.isEmpty())stopSelf(startId)
            return START_NOT_STICKY
        }
        super.onStartCommand(intent,flags,startId)
        if(intent?.action==PlaybackRuntime.START) {
            val request=PlaybackRuntime.take(intent.getStringExtra("request"))
            if(request!=null)start(request)else if(entries.isEmpty())stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun updateIdleNotificationMode(keep: Boolean) {
        if(keepIdleNotification==keep)return
        keepIdleNotification=keep
        setShowNotificationForIdlePlayer(if(keep)SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS else SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
    }
    private fun notificationStopUri(generation: Int)=Uri.Builder().scheme("vkmusic").authority("notification-stop")
        .appendPath(notificationSessionId).appendPath(generation.toString()).build()
    private fun scopedNotification(notification: MediaNotification,generation: Int): MediaNotification {
        val intent=Intent(this,MusicPlaybackService::class.java).setAction(NOTIFICATION_STOP).setData(notificationStopUri(generation))
        notification.notification.deleteIntent=PendingIntent.getService(this,generation,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return MediaNotification(generation,notification.notification)
    }
    @Suppress("DEPRECATION")
    private fun isStaleNotificationStop(intent: Intent?): Boolean {
        if(intent?.action!=Intent.ACTION_MEDIA_BUTTON)return false
        val key=intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if(key?.keyCode!=KeyEvent.KEYCODE_MEDIA_STOP)return false
        val current=PlaybackPendingIntentBuilder.createMediaButtonIntent(this,Player.COMMAND_STOP,null,
            notificationSessionId,MusicPlaybackService::class.java).data
        val fallback=PlaybackPendingIntentBuilder.createMediaButtonIntent(this,Player.COMMAND_STOP,null,
            null,MusicPlaybackService::class.java).data
        val dismissed=intent.getBooleanExtra(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY,false)
        // Unaddressed hardware/widget events retain Media3's normal fallback.
        return intent.data!=current&&(dismissed||intent.data!=null&&intent.data!=fallback)
    }
    @Suppress("DEPRECATION")
    private fun buttonDetails(intent: Intent?): String {
        val key=intent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        return "key=${key?.keyCode},action=${key?.action},eventTime=${key?.eventTime},data=${intent?.data},dismiss=${intent?.getBooleanExtra(androidx.media3.session.MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY,false)}"
    }
    private fun installMetadata(id: String,items: List<PlaybackEntry>,session: String?) {
        suppressCheckpoint=true;serial++;queueId=id;queueOrder=emptyList();savedOrder=emptyList();lastSavedOrder=null;lastCursor=null
        entries=items;readSession=session;failure=null;artWork?.cancel();artwork=null;lastArtIndex=-1;loadingIndex=-1;refreshedIndex=-1
    }
    private fun start(request: PlaybackRuntime.Request) {
        val record=request.restored
        installMetadata(record?.id?:request.id,request.selection.entries,request.session)
        // Finish restoring the playlist configuration before the playback thread
        // prepares or plays it. Applying the saved order from publish() is too late.
        pendingOrder=null
        player.setMediaSources(sources(),request.selection.start,request.position)
        if(record!=null) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(record.order.toIntArray(),0L))
            player.shuffleModeEnabled=record.cursor.shuffle;player.repeatMode=record.cursor.repeat
        }
        suppressCheckpoint=false
        player.prepare();player.play();loadCover();publish(refreshOrder=true,forceCheckpoint=true)
    }
    private fun resumeFromHistory(forPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val result=SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val g=PlaybackRuntime.commandGeneration;val before=serial
        val job=scope.launch {
            try {
                val record=PlaybackRuntime.restoreHistory()?:throw IllegalStateException("NO_PLAYBACK_HISTORY")
                ensureActive()
                check(g==PlaybackRuntime.commandGeneration&&serial==before&&player.mediaItemCount==0)
                if(forPlayback) {
                    installMetadata(record.id,record.entries,record.session);pendingOrder=record.order
                    player.shuffleModeEnabled=record.cursor.shuffle;player.repeatMode=record.cursor.repeat
                    result.set(MediaSession.MediaItemsWithStartPosition(entries.mapIndexed(::item),record.cursor.index,record.cursor.positionMs))
                }else {
                    val entry=record.entries[record.cursor.index]
                    result.set(MediaSession.MediaItemsWithStartPosition(listOf(item(record.cursor.index,entry)),0,record.cursor.positionMs))
                }
            }catch(e: CancellationException){result.cancel(false);throw e}catch(_: Exception){result.setException(IllegalStateException("PLAYBACK_RESUMPTION_UNAVAILABLE"))}
        }
        result.addListener({if(result.isCancelled)job.cancel()},MoreExecutors.directExecutor())
        return result
    }
    private fun item(index: Int,entry: PlaybackEntry)=MediaItem.Builder().setMediaId("listen:$serial:$index")
        .setUri("vkmusic://queue/$serial/$index")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.track.displayTitle).setArtist(entry.track.artist)
            .setAlbumTitle(entry.track.album?.title).setDescription(entry.sourceLabel).build()).build()
    private fun sources(): List<MediaSource> = entries.mapIndexed {i,entry->source(i,entry)}
    private fun source(i: Int,entry: PlaybackEntry): MediaSource {
        val expectedSession=readSession;val run=serial
        return PlaybackMediaSource(item(i,entry),onlineSourceFactory,localFactory=offlineSourceFactory) {
                try {
                    val uri=if(entry.offline) {
                        musicCheck(!MaintenanceRuntime.busy,MusicError.BUSY)
                        withContext(Dispatchers.IO) {
                            val saved=checkNotNull(entry.saved)
                            val current=AudioExportStore(this@MusicPlaybackService).use{it.existing(saved.taskKey)}?:saved
                            musicCheck(current.track.id==entry.track.id,MusicError.STORAGE)
                            SharedAudioStore(this@MusicPlaybackService).requireValid(current)
                            Uri.parse(current.uri)
                        }
                    }else {
                        val local=withContext(Dispatchers.IO) {
                            val stored=MusicAccessStore(this@MusicPlaybackService).read()?:throw MusicFailure(MusicError.ACCESS)
                            musicCheck(stored.readSessionId==expectedSession&&(stored.accountId==null||stored.accountId==entry.account),MusicError.ACCOUNT_CHANGED)
                            if(MaintenanceRuntime.busy)null else AudioExportStore(this@MusicPlaybackService).use {store->
                                store.copies(entry.track).firstOrNull {file->
                                    file.source?.account==entry.account&&try{SharedAudioStore(this@MusicPlaybackService).requireValid(file);true}catch(e: CancellationException){throw e}catch(_: Exception){false}
                                }?.uri
                            }
                        }
                        if(local!=null)return@PlaybackMediaSource Uri.parse(local)
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
                    withContext(Dispatchers.Main){if(serial==run&&player.currentMediaItemIndex==i)failure=if(entry.offline&&(e as? MusicFailure)?.code!=MusicError.BUSY)"Сохранённый файл изменился или недоступен. Обнови список и проверь сохранённые файлы." else (e as? MusicFailure)?.code?.label?:"Не удалось открыть аудио."}
                    throw e
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
    private fun timelineOrder(shuffle: Boolean): List<Int> {
        val timeline=player.currentTimeline
        val ordered=mutableListOf<Int>();val seen=mutableSetOf<Int>()
        var i=timeline.getFirstWindowIndex(shuffle)
        while(i!=C.INDEX_UNSET&&i in entries.indices&&seen.add(i)) {
            ordered.add(i);i=timeline.getNextWindowIndex(i,Player.REPEAT_MODE_OFF,shuffle)
        }
        return if(ordered.size==entries.size)ordered else entries.indices.toList()
    }
    private fun publish(refreshOrder: Boolean=false,forceCheckpoint: Boolean=false) {
        if(!::player.isInitialized||entries.isEmpty())return
        pendingOrder?.takeIf{player.mediaItemCount==entries.size}?.let {order->
            pendingOrder=null;player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(),0L))
            queueOrder=emptyList();savedOrder=emptyList();suppressCheckpoint=false
        }
        if(refreshOrder||queueOrder.size!=entries.size) {
            savedOrder=timelineOrder(true)
            queueOrder=if(player.shuffleModeEnabled)savedOrder else entries.indices.toList()
        }
        val previous=PlaybackRuntime.state.value
        val duration=player.duration.takeIf{it!=C.TIME_UNSET&&it>0}?:entries.getOrNull(player.currentMediaItemIndex)?.track?.duration?.toLong()?.times(1000)?:0
        PlaybackRuntime.publish(previous.copy(entries=entries,index=player.currentMediaItemIndex.coerceAtLeast(0),playing=player.isPlaying,
            buffering=player.playbackState==Player.STATE_BUFFERING,playWhenReady=player.playWhenReady,positionMs=player.currentPosition.coerceAtLeast(0),durationMs=duration,
            bufferedMs=player.bufferedPosition.coerceAtLeast(0),seekable=player.isCurrentMediaItemSeekable,shuffle=player.shuffleModeEnabled,
            repeat=player.repeatMode,previous=player.hasPreviousMediaItem()||player.currentPosition>3000,next=player.hasNextMediaItem(),error=failure,artwork=artwork,order=queueOrder))
        saveCheckpoint(forceCheckpoint)
    }
    private fun saveCheckpoint(force: Boolean=false) {
        val id=queueId?:return
        if(suppressCheckpoint||pendingOrder!=null||entries.isEmpty()||player.mediaItemCount!=entries.size||savedOrder.size!=entries.size)return
        val cursor=PlaybackCursor(player.currentMediaItemIndex.coerceAtLeast(0),player.currentPosition.coerceAtLeast(0),player.shuffleModeEnabled,player.repeatMode)
        val previous=lastCursor;val now=SystemClock.elapsedRealtime()
        val changed=previous==null||previous.index!=cursor.index||previous.shuffle!=cursor.shuffle||previous.repeat!=cursor.repeat||lastSavedOrder!==savedOrder&&lastSavedOrder!=savedOrder
        if(force||changed||now-lastCheckpointAt>=5000&&previous!=cursor) {
            PlaybackRuntime.checkpoint(this,PlaybackCheckpoint(id,entries,readSession,cursor,savedOrder))
            lastCursor=cursor;lastSavedOrder=savedOrder;lastCheckpointAt=now
        }
    }
    internal fun toggle() {failure=null;if(player.isPlaying||player.playWhenReady&&player.playbackState==Player.STATE_BUFFERING)player.pause()else{player.prepare();player.play()};publish(forceCheckpoint=true)}
    internal fun playIndex(index: Int){if(index in entries.indices){failure=null;player.seekTo(index,0);player.prepare();player.play();publish(forceCheckpoint=true)}}
    internal fun next(){if(player.hasNextMediaItem()){failure=null;player.seekToNextMediaItem();player.prepare();player.play();publish(forceCheckpoint=true)}}
    internal fun previous(){failure=null;player.seekToPrevious();player.prepare();player.play();publish(forceCheckpoint=true)}
    internal fun seek(position: Long){if(player.isCurrentMediaItemSeekable){player.seekTo(position.coerceIn(0,player.duration.coerceAtLeast(0)));publish(forceCheckpoint=true)}}
    internal fun shuffle(){player.shuffleModeEnabled=!player.shuffleModeEnabled;publish(refreshOrder=true,forceCheckpoint=true)}
    internal fun repeat(){player.repeatMode=when(player.repeatMode){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF};publish()}
    internal fun stopPlayback(){serial++;queueId=null;pendingOrder=null;entries=emptyList();artWork?.cancel();player.stop();player.clearMediaItems();stopSelf()}
    override fun onDestroy() {
        destroyed=true
        trace{"destroy"}
        saveCheckpoint(force=true)
        scope.cancel();artWork?.cancel();mediaSession?.release();mediaSession=null;player.release()
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        retiredNotificationIds.forEach{getSystemService(NotificationManager::class.java).cancel(it)};retiredNotificationIds.clear()
        if(PlaybackRuntime.service===this){PlaybackRuntime.service=null;PlaybackRuntime.publish(PlaybackRuntime.state.value.copy(playing=false,buffering=false,playWhenReady=false))}
        super.onDestroy()
    }
}
