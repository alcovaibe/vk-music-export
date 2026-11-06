package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*

/** Owns only visible-card probes. All lifecycle changes and callbacks run on the UI dispatcher. */
internal class FriendAvailabilityReader(
    private val scope: CoroutineScope,
    private val api: MusicApi,
    private val cache: FriendAvailabilityCache,
    private val checked: suspend (MusicAccess)->MusicAccess,
    private val blocked: ()->Boolean,
    private val publish: (Long,FriendAudioAccess)->Unit,
    private val changedSession: ()->Unit,
    private val report: (FriendMusicProblem)->Unit = {}
) {
    private var access: MusicAccess?=null
    private var active=false
    private var visible=emptyList<Long>()
    private var halted=false
    private var epoch=0L
    private var work: Job?=null
    private var attempted=mutableSetOf<Long>()

    fun bind(initial: MusicAccess,force: Boolean=false) {
        stop();access=initial
        if(force)cache.clear()
        cache.activate(initial.readSessionId,initial.accountId)
        start()
    }
    fun clear(){stop();access=null;visible=emptyList();cache.clear()}
    fun setActive(value: Boolean) {
        if(active!=value){active=value;if(!value)stop()}
        if(value)start()
    }
    fun visible(ids: List<Long>) {
        val next=ids.distinct().take(32)
        if(next==visible)return
        stop();visible=next;start()
    }
    private fun stop() {
        epoch++;work?.cancel();work=null;attempted=mutableSetOf();halted=false
    }
    private fun start() {
        val initial=access?:return
        if(!active||halted||visible.isEmpty())return
        if(work?.isActive==true)return
        val version=epoch
        val tried=attempted
        val inFlight=mutableSetOf<Long>()
        suspend fun guard(): MusicAccess {
            currentCoroutineContext().ensureActive()
            if(version!=epoch||!active)throw CancellationException()
            return checked(initial).also{
                currentCoroutineContext().ensureActive()
                if(version!=epoch||!active)throw CancellationException()
            }
        }
        work=scope.launch(start=CoroutineStart.LAZY) {
            try {coroutineScope {
                repeat(2) {launch {
                    while(isActive&&!halted) {
                        // Pausing the coroutine cancels the current HTTP Call through the existing transport.
                        while(blocked()){guard();delay(150)}
                        guard()
                        val id=visible.firstOrNull{it !in tried&&it !in inFlight}?:break
                        val cached=cache.get(id)
                        if(cached!=null){tried.add(id);publish(id,cached);continue}
                        inFlight.add(id)
                        try {
                            api.probeFriendAudio(guard(),id)
                            guard()
                            cache.put(id,FriendAudioAccess.AVAILABLE)
                            publish(id,FriendAudioAccess.AVAILABLE)
                        }catch(e: CancellationException){throw e}
                         catch(e: MusicReadChanged){throw e}
                         catch(e: Exception) {
                            // Even a denial/error from an old login must not populate the new session.
                            guard()
                            val issue=FriendMusicProblem.from(e)
                            val status=if(issue.remoteCode==201||issue.code==MusicError.DENIED)
                                FriendAudioAccess.DENIED else FriendAudioAccess.CHECK_FAILED
                            cache.put(id,status);publish(id,status);report(issue)
                            if(issue.code in setOf(MusicError.RATE_LIMIT,MusicError.ACCESS,
                                    MusicError.INVALID_ACCESS,MusicError.ACCOUNT_CHANGED))halted=true
                        }finally{inFlight.remove(id);tried.add(id)}
                    }
                }}
            }}catch(e: CancellationException){throw e}
             catch(_: MusicReadChanged) {
                if(version==epoch){stop();access=null;cache.clear();changedSession()}
             }catch(e: Exception) {
                if(version==epoch) {
                    val issue=FriendMusicProblem.from(e);report(issue)
                    if(issue.code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.ACCOUNT_CHANGED)) {
                        stop();access=null;cache.clear();changedSession()
                    }
                }
             }
        }.also{it.start()}
    }
}
