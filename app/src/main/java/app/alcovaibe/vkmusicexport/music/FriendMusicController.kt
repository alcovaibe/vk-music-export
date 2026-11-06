package app.alcovaibe.vkmusicexport.music

import android.app.Application
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only remote profiles. File publication belongs to the existing persisted download job. */
internal class FriendMusicController(private val application: Application,private val api: MusicApi,private val scope: CoroutineScope,
    private val onDownloads: ()->Unit={},private val queueStart: suspend()->Unit={AudioExportRuntime.start(application)},
    clock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val blocked: ()->Boolean={false}) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application)else null
    private val mutable=MutableStateFlow(FriendMusicState());val state=mutable.asStateFlow()
    private var work: Job?=null;private var previewWork: Job?=null;private var generation=0;private var lease: MusicAccess?=null
    private val availabilityCache=FriendAvailabilityCache()
    private val exportReads=ExportReadCache(clock)
    private val sourceReads=MusicSourceReader(api,exportReads)
    private var visibleFriendIds=emptyList<Long>()
    private var previewWake: Channel<Unit>?=null
    fun reset(){
        generation++;work?.cancel();previewWork?.cancel();previewWake?.close();previewWake=null
        previewWork=null;work=null;lease=null;visibleFriendIds=emptyList()
        availabilityCache.clear();exportReads.clear();mutable.value=FriendMusicState()
    }
    fun pauseRead(){generation++;work?.cancel();work=null;mutable.update{it.copy(busy=false,preparing=false,selecting=false,selection=emptySet())}}
    fun back() {
        if(state.value.selecting){mutable.update{it.copy(selecting=false,selection=emptySet())};return}
        pauseRead()
        mutable.update{if(it.playlist!=null)it.copy(playlist=null,playlistTracks=null,playlistComplete=false,playlistProblem=null,musicQuery="",message=null)
            else it.copy(user=null,tracks=null,tracksComplete=false,catalog=null,problems=emptyMap(),musicQuery="",message=null)}
        previewWake?.trySend(Unit)
    }
    fun prioritizeVisible(ids: List<Long>) {
        val prioritized=ids.distinct().take(32)
        if(visibleFriendIds!=prioritized){visibleFriendIds=prioritized;previewWake?.trySend(Unit)}
    }
    fun friendsQuery(value: String){mutable.update{it.copy(friendsQuery=value.take(200))};previewWake?.trySend(Unit)}
    fun musicQuery(value: String){mutable.update{it.copy(musicQuery=value.take(200))}}
    fun tab(value: FriendMusicTab){mutable.update{it.copy(tab=value,playlist=null,playlistTracks=null,playlistComplete=false,playlistProblem=null,selecting=false,selection=emptySet(),musicQuery="",message=null)}}
    fun selectMode(){mutable.update{it.copy(selecting=!it.selecting,selection=emptySet())}}
    fun toggle(position: Int){mutable.update{if(position in 1..(it.page?.tracks?.size?:0))it.copy(selecting=true,selection=if(position in it.selection)it.selection-position else it.selection+position)else it}}
    fun refresh(){state.value.user?.let{open(it,force=true)}?:loadFriends(forceStatuses=true)}
    private suspend fun checked(initial: MusicAccess): MusicAccess {
        currentCoroutineContext().ensureActive()
        return (sessions?.continueRead(initial)?:withContext(Dispatchers.IO){vault.read()?:throw MusicFailure(MusicError.ACCESS)}.also{
            if(it.readSessionId!=initial.readSessionId)throw MusicReadChanged()
        }).also{currentCoroutineContext().ensureActive()}
    }
    private fun run(preparing: Boolean=false,body: suspend(MusicAccess,suspend()->MusicAccess)->Unit) {
        if(preparing&&(blocked()||MaintenanceRuntime.busy||DuplicateCheckRuntime.transferring)){mutable.update{it.copy(message=MusicError.BUSY.label)};return}
        work?.cancel();val g=++generation
        mutable.update{it.copy(busy=true,preparing=preparing,message=null)}
        work=scope.launch {
            try {
                val initial=lease?:withContext(Dispatchers.IO){sessions?.current()?:vault.read()?:throw MusicFailure(MusicError.ACCESS)}.also{lease=it}
                val guard: suspend()->MusicAccess={checked(initial).also{if(g!=generation)throw CancellationException()}}
                mutable.update{it.copy(viewerAccount=initial.accountId,readSessionId=initial.readSessionId)}
                withTimeout(5*60_000L){body(guard(),guard);guard()}
            }catch(e: TimeoutCancellationException){if(g==generation)problem(FriendMusicProblem(MusicError.TIMEOUT))}
             catch(e: CancellationException){throw e}
             catch(e: MusicReadChanged){if(g==generation){
                 previewWork?.cancel();previewWake?.close();previewWake=null
                 availabilityCache.clear();exportReads.clear();lease=null
                 mutable.value=FriendMusicState(listProblem=FriendMusicProblem(MusicError.ACCOUNT_CHANGED))
             }}
             catch(e: Exception){if(g==generation) {
                  val error=FriendMusicProblem.from(e)
                  Diagnostics.event("music_friend_load_failure",mapOf("reason" to error.code.name,"vk_code" to error.remoteCode))
                  problem(error)
              }}
            finally{if(g==generation){mutable.update{it.copy(busy=false,preparing=false)};work=null}}
        }
    }
    private fun problem(value: FriendMusicProblem){mutable.update{when {
        it.preparing->it.copy(message=value.description)
        it.user==null->it.copy(listProblem=value)
        it.playlist!=null->it.copy(playlistProblem=value)
        else->it.copy(problems=it.problems+(it.tab to value))
    }}}
    fun loadFriends(forceStatuses: Boolean=false) {
        previewWork?.cancel();previewWake?.close();previewWake=null;previewWork=null
        visibleFriendIds=emptyList()
        mutable.update{it.copy(user=null,friends=emptyList(),friendAccess=emptyMap(),
            friendsComplete=false,friendsTotal=null,listProblem=null)}
        run {_,guard->
            val rows=mutableListOf<MusicFriend>();val seen=mutableSetOf<Long>();var total: Int?=null
            var access: MusicAccess?=null
            do {
                val current=guard()
                if(access==null) {
                    access=current
                    availabilityCache.activate(current.readSessionId,current.accountId)
                }
                val page=api.friendsPage(current,rows.size);guard()
                musicCheck(total==null||total==page.count,MusicError.LIBRARY_CHANGED);total=page.count
                musicCheck(rows.size+page.items.size<=page.count,MusicError.LIBRARY_CHANGED)
                musicCheck(page.items.isNotEmpty()||rows.size==page.count,MusicError.INCOMPLETE)
                page.items.forEach{musicCheck(seen.add(it.id),MusicError.INCOMPLETE)};rows.addAll(page.items)
                val restored=availabilityCache.snapshot(rows)
                mutable.update{state->
                    state.copy(friends=rows.toList(),friendsTotal=page.count,
                        friendAccess=state.friendAccess.filterKeys{it in seen}+restored)
                }
                // Begin previewing after the very first page instead of waiting for the full list.
                if(previewWork==null&&rows.isNotEmpty())previewFriends(current,forceStatuses)
                previewWake?.trySend(Unit)
            }while(rows.size<total!!)
            mutable.update{it.copy(friendsComplete=true)}
            previewWake?.trySend(Unit)
            Diagnostics.event("music_friends_loaded",mapOf("count" to rows.size))
        }
    }

    /** Two in-flight reads; VkAudioClient still enforces its global 350ms request pacing. */
    private fun previewFriends(initial: MusicAccess,forceStatuses: Boolean) {
        previewWork?.cancel();previewWake?.close()
        val wake=Channel<Unit>(Channel.CONFLATED)
        previewWake=wake
        val inFlight=mutableSetOf<Long>();val attempted=mutableSetOf<Long>()
        val mutex=Mutex();var halted=false
        previewWork=scope.launch {
            coroutineScope {
                repeat(2) {
                    launch {
                        while(isActive) {
                            val candidate=mutex.withLock {
                                val snapshot=mutable.value
                                if(halted||snapshot.user!=null)null
                                else FriendAvailabilityPriority.next(snapshot.friends,visibleFriendIds,
                                    snapshot.friendsQuery){id->
                                    id in attempted||id in inFlight||
                                        (!forceStatuses&&availabilityCache.get(id)!=null)
                                }?.also{inFlight.add(it.id)}
                            }
                            if(candidate==null) {
                                val finished=mutex.withLock {
                                    halted||((mutable.value.friendsComplete||work?.isActive!=true)&&
                                        mutable.value.friends.all{friend->
                                            friend.id in attempted||friend.id in inFlight||
                                                (!forceStatuses&&availabilityCache.get(friend.id)!=null)
                                        })
                                }
                                if(finished)break
                                if(wake.receiveCatching().getOrNull()==null)break
                                continue
                            }
                            try {
                                // Foreground library and selected profile requests take precedence
                                // over cosmetic status badges; keep the global VK pacer unchanged.
                                while(blocked()||mutable.value.user!=null)delay(150)
                                val current=checked(initial)
                                api.probeFriendAudio(current,candidate.id)
                                checked(initial)
                                availabilityCache.put(candidate.id,FriendAudioAccess.AVAILABLE)
                                mutable.update{state->if(state.friends.any{it.id==candidate.id})
                                    state.copy(friendAccess=state.friendAccess+(candidate.id to FriendAudioAccess.AVAILABLE))
                                    else state}
                            }catch(e: CancellationException){throw e}
                             catch(_: MusicReadChanged){
                                mutex.withLock{halted=true}
                                availabilityCache.clear()
                                mutable.update{it.copy(friendAccess=emptyMap(),
                                    listProblem=FriendMusicProblem(MusicError.ACCOUNT_CHANGED))}
                                wake.trySend(Unit)
                             }catch(e: Exception) {
                                val issue=FriendMusicProblem.from(e)
                                val status=if(issue.remoteCode==201||issue.code==MusicError.DENIED)
                                    FriendAudioAccess.DENIED else FriendAudioAccess.CHECK_FAILED
                                if(status==FriendAudioAccess.DENIED)
                                    availabilityCache.put(candidate.id,status)
                                mutable.update{state->if(state.friends.any{it.id==candidate.id})
                                    state.copy(friendAccess=state.friendAccess+(candidate.id to status))
                                    else state}
                                Diagnostics.event("music_friend_access_preview",
                                    mapOf("reason" to issue.code.name,"vk_code" to issue.remoteCode))
                                if(issue.code in setOf(MusicError.RATE_LIMIT,MusicError.ACCESS,
                                        MusicError.INVALID_ACCESS,MusicError.ACCOUNT_CHANGED)) {
                                    mutex.withLock{halted=true}
                                    // Unchecked friends are not denied; the preview is simply unavailable.
                                    mutable.update{state->state.copy(friendAccess=state.friendAccess+
                                        state.friends.filterNot{it.id in state.friendAccess}
                                            .associate{it.id to FriendAudioAccess.CHECK_FAILED})}
                                    wake.trySend(Unit)
                                }
                            }finally{
                                mutex.withLock{inFlight.remove(candidate.id);attempted.add(candidate.id)}
                                wake.trySend(Unit)
                            }
                        }
                    }
                }
            }
        }
        wake.trySend(Unit)
    }

    fun open(user: MusicFriend,force: Boolean=false) {
        mutable.update{it.copy(user=user,tab=FriendMusicTab.TRACKS,tracks=null,tracksComplete=false,catalog=null,playlist=null,playlistTracks=null,
            playlistComplete=false,problems=emptyMap(),playlistProblem=null,selecting=false,selection=emptySet(),musicQuery="")}
        previewWake?.trySend(Unit)
        run {initial,guard->
            val viewer=initial.accountId?:api.accountOwner(guard())
            guard();mutable.update{it.copy(viewerAccount=viewer)}
            if(force)exportReads.begin(viewer,initial.readSessionId)
            val fresh=api.friendProfile(guard(),user.id);guard();mutable.update{it.copy(user=fresh)}
            if(!fresh.accessible)exportReads.clear()
            try {
                musicCheck(fresh.accessible,MusicError.DENIED)
                val page=sourceReads.library(guard(),viewer,user.id,guard={guard();Unit},partial={part->mutable.update{it.copy(tracks=part)}})
                guard();availabilityCache.put(user.id,FriendAudioAccess.AVAILABLE)
                mutable.update{it.copy(tracks=page,tracksComplete=true,friendAccess=it.friendAccess+(user.id to FriendAudioAccess.AVAILABLE))}
            }catch(e: CancellationException){throw e}catch(e: MusicReadChanged){throw e}catch(e: Exception){
                val issue=FriendMusicProblem.from(e)
                val denied=issue.remoteCode==201||issue.code==MusicError.DENIED
                if(denied)availabilityCache.put(user.id,FriendAudioAccess.DENIED)
                mutable.update{it.copy(problems=it.problems+(FriendMusicTab.TRACKS to issue),
                    friendAccess=if(denied)it.friendAccess+(user.id to FriendAudioAccess.DENIED)else it.friendAccess)}
            }
            try {
                musicCheck(fresh.accessible,MusicError.DENIED)
                val catalog=sourceReads.catalog(guard(),viewer,user.id,personal=false,guard={guard();Unit},partial={part->mutable.update{it.copy(catalog=part)}})
                guard();mutable.update{it.copy(catalog=catalog)}
                val issue=if(!catalog.complete)MusicError.INCOMPLETE else if(catalog.lists.any{it.kind==PlaylistKind.UNKNOWN})MusicError.COLLECTION_TYPE else null
                if(issue!=null)mutable.update{it.copy(problems=it.problems+listOf(FriendMusicTab.PLAYLISTS,FriendMusicTab.ALBUMS).associateWith{FriendMusicProblem(issue)})}
            }catch(e: CancellationException){throw e}catch(e: MusicReadChanged){throw e}catch(e: Exception){mutable.update{it.copy(problems=it.problems+listOf(FriendMusicTab.PLAYLISTS,FriendMusicTab.ALBUMS).associateWith{FriendMusicProblem.from(e)})}}
        }
    }
    fun playlist(list: NativePlaylist) {
        if(list.kind==PlaylistKind.UNKNOWN)return
        if(state.value.catalog?.lists?.none{it.key==list.key}!=false)return
        mutable.update{it.copy(playlist=list,playlistTracks=null,playlistComplete=false,playlistProblem=null,selection=emptySet(),selecting=false,musicQuery="")}
        run {initial,guard->
            val viewer=initial.accountId?:api.accountOwner(guard())
            val read=sourceReads.playlist(guard(),viewer,list,guard={guard();Unit},partial={part->mutable.update{it.copy(playlistTracks=part)}})
            guard();mutable.update{it.copy(playlist=read.list,playlistTracks=read.page,playlistComplete=true,
                catalog=it.catalog?.let{catalog->catalog.copy(lists=catalog.lists.map{row->if(row.key==read.list.key)read.list else row})})}
        }
    }
    fun download(positions: Set<Int>?=null) {
        val before=state.value
        if(before.user==null||before.page==null||before.preparing||positions?.isEmpty()==true)return
        if(positions==null&&(!before.complete||before.busy))return
        run(preparing=true){_,guard->withContext(Dispatchers.IO){
            AudioExportRuntime.initialize(application)
            val queue=AudioExportRuntime.state.value
            musicCheck(!blocked()&&!queue.unfinished&&queue.status !in setOf("RUNNING","QUEUED"),MusicError.BUSY)
            val proofAccess=guard();val viewer=api.accountOwner(proofAccess);guard()
            val prepared=FriendDownloadPreparation(api,exportReads).plan(before,proofAccess,viewer,positions,guard={guard();Unit})
            guard();musicCheck(!blocked(),MusicError.BUSY)
            if(prepared.tasks.isEmpty()) {
                withContext(Dispatchers.Main){publishPrepared(before,prepared,viewer,false)}
                return@withContext
            }
            AudioExportRuntime.replace(application,prepared.tasks,account=viewer)
            guard();queueStart()
            withContext(Dispatchers.Main){publishPrepared(before,prepared,viewer,true);onDownloads()}
        }}
    }
    private fun publishPrepared(before: FriendMusicState,prepared: PreparedFriendDownload,viewer: Long,queued: Boolean) {
        mutable.update{state->
            val updated=prepared.page?.let{page->if(before.playlist==null)state.copy(tracks=page,tracksComplete=true)
                else state.copy(playlist=prepared.list,playlistTracks=page,playlistComplete=true,
                    catalog=state.catalog?.let{catalog->catalog.copy(lists=catalog.lists.map{row->prepared.list?.takeIf{it.key==row.key}?:row})})}?:state
            updated.copy(viewerAccount=viewer,selecting=false,selection=emptySet(),message=if(queued)
                "Очередь подготовлена по полученным данным. Скачивание продолжается в фоне." else "Доступных треков для скачивания нет.")
        }
    }
}
