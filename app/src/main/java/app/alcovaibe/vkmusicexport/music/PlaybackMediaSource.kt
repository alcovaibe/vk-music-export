package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.*
import java.io.IOException

/** Validate every redirect and every HLS resource. Media requests never inherit VK API credentials. */
internal object PlaybackNetwork {
    fun client()=NetworkClients.media().newBuilder().addInterceptor {chain->
        var request=chain.request()
        for(hop in 0..5) {
            MediaAddress.require(request.url.toString())
            val response=chain.proceed(request)
            if(response.code !in setOf(301,302,303,307,308))return@addInterceptor response
            val target=try {
                if(hop==5)throw IOException("PLAYBACK_REDIRECT_LIMIT")
                MediaAddress.resolve(request.url.toString(),response.header("Location")?:throw IOException("PLAYBACK_REDIRECT"))
            }finally{response.close()}
            request=request.newBuilder().url(target).build()
        }
        throw IOException("PLAYBACK_REDIRECT_LIMIT")
    }.build()
}

/** Resolve only when the player prepares this item, on IO, with a fresh checked read session. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackMediaSource(private var publicItem: MediaItem,private val factory: MediaSource.Factory,
    private val resolve: suspend()->Uri): CompositeMediaSource<Unit>() {
    private var child: MediaSource?=null
    private var failure: IOException?=null
    private var lastTimeline: Timeline?=null
    private var scope: CoroutineScope?=null
    override fun getMediaItem()=publicItem
    override fun canUpdateMediaItem(mediaItem: MediaItem)=mediaItem.mediaId==publicItem.mediaId&&mediaItem.localConfiguration?.uri==publicItem.localConfiguration?.uri
    override fun updateMediaItem(mediaItem: MediaItem) {
        publicItem=mediaItem
        lastTimeline?.let(::publishTimeline)
    }
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        failure=null
        val handler=Handler(checkNotNull(Looper.myLooper()))
        val currentScope=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope=currentScope
        currentScope.launch {
            try {
                val uri=resolve();ensureActive()
                handler.post {
                    if(scope!==currentScope||!currentScope.isActive)return@post
                    try {
                        val resolved=factory.createMediaSource(publicItem.buildUpon().setUri(uri).build())
                        child=resolved;prepareChildSource(Unit,resolved)
                    }catch(_: Exception){failure=IOException("PLAYBACK_FORMAT")}
                }
            }catch(e: CancellationException){throw e}
             catch(_: Exception){handler.post{if(scope===currentScope)failure=IOException("PLAYBACK_SOURCE")}}
        }
    }
    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let{throw it}
        super.maybeThrowSourceInfoRefreshError()
    }
    override fun onChildSourceInfoRefreshed(id: Unit,mediaSource: MediaSource,timeline: Timeline) {
        lastTimeline=timeline;publishTimeline(timeline)
    }
    private fun publishTimeline(timeline: Timeline) {
        // Public timelines and media sessions expose an opaque media ID, never a signed CDN address.
        refreshSourceInfo(object: ForwardingTimeline(timeline) {
            override fun getWindow(windowIndex: Int,window: Timeline.Window,defaultPositionProjectionUs: Long): Timeline.Window {
                super.getWindow(windowIndex,window,defaultPositionProjectionUs)
                window.mediaItem=publicItem
                return window
            }
        })
    }
    override fun createPeriod(id: MediaSource.MediaPeriodId,allocator: Allocator,startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id,allocator,startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod){child?.releasePeriod(mediaPeriod)}
    override fun releaseSourceInternal() {
        scope?.cancel();scope=null
        super.releaseSourceInternal();child=null;failure=null;lastTimeline=null
    }
    companion object {
        fun factory(context: Context)=DefaultMediaSourceFactory(context).setDataSourceFactory(
            DefaultDataSource.Factory(context,OkHttpDataSource.Factory(PlaybackNetwork.client()).setUserAgent("VK-Music-Export-Player/1")))
    }
}
