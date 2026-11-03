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
    private val blocked: ()->Boolean={false}) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application)else null
    private val mutable=MutableStateFlow(FriendMusicState());val state=mutable.asStateFlow()
    private var work: Job?=null;private var previewWork: Job?=null;private var generation=0;private var lease: MusicAccess?=null
    private val availabilityCache=FriendAvailabilityCache()
    private var visibleFriendIds=emptyList<Long>()
    private var previewWake: Channel<Unit>?=null
    fun reset(){
        generation++;work?.cancel();previewWork?.cancel();previewWake?.close();previewWake=null
        previewWork=null;work=null;lease=null;visibleFriendIds=emptyList()
        availabilityCache.clear();mutable.value=FriendMusicState()
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
    fun refresh(){state.value.user?.let{open(it)}?:loadFriends()}
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
                mutable.update{it.copy(viewerAccount=initial.accountId)}
                withTimeout(5*60_000L){body(guard(),guard);guard()}
            }catch(e: TimeoutCancellationException){if(g==generation)problem(FriendMusicProblem(MusicError.TIMEOUT))}
             catch(e: CancellationException){throw e}
             catch(e: MusicReadChanged){if(g==generation){
                 previewWork?.cancel();previewWake?.close();previewWake=null
                 availabilityCache.clear();lease=null
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
    fun loadFriends() {
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
                if(previewWork==null&&rows.isNotEmpty())previewFriends(current)
                previewWake?.trySend(Unit)
            }while(rows.size<total!!)
            mutable.update{it.copy(friendsComplete=true)}
            previewWake?.trySend(Unit)
            Diagnostics.event("music_friends_loaded",mapOf("count" to rows.size))
        }
    }

    /** Two in-flight reads; VkAudioClient still enforces its global 350ms request pacing. */
    private fun previewFriends(initial: MusicAccess) {
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
                                    id in attempted||id in inFlight||availabilityCache.get(id)!=null
                                }?.also{inFlight.add(it.id)}
                            }
                            if(candidate==null) {
                                val finished=mutex.withLock {
                                    halted||((mutable.value.friendsComplete||work?.isActive!=true)&&
                                        mutable.value.friends.all{friend->
                                            friend.id in attempted||friend.id in inFlight||
                                                availabilityCache.get(friend.id)!=null
                                        })
                                }
                                if(finished)break
                                if(wake.receiveCatching().getOrNull()==null)break
                                continue
                            }
                            try {
                                val current=checked(initial)
                                api.libraryPage(current,candidate.id,0)
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

    private suspend fun tracks(fetch:suspend(Int)->NativePage,guard:suspend()->MusicAccess,playlist: Boolean): NativePage {
        val rows=mutableListOf<NativeTrack>()
        return MusicPagination.tracks({offset->
            val page=fetch(offset);guard();rows.addAll(page.tracks)
            mutable.update{if(playlist)it.copy(playlistTracks=NativePage(rows.toList(),page.count))else it.copy(tracks=NativePage(rows.toList(),page.count))}
            page
        })
    }
    fun open(user: MusicFriend) {
        mutable.update{it.copy(user=user,tab=FriendMusicTab.TRACKS,tracks=null,tracksComplete=false,catalog=null,playlist=null,playlistTracks=null,
            playlistComplete=false,problems=emptyMap(),playlistProblem=null,selecting=false,selection=emptySet(),musicQuery="")}
        previewWake?.trySend(Unit)
        run {_,guard->
            val fresh=api.friendProfile(guard(),user.id);guard();mutable.update{it.copy(user=fresh)}
            try {
                val page=tracks({offset->api.libraryPage(guard(),user.id,offset)},guard,false)
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
                val catalog=MusicCatalog.load(api,guard(),user.id,guard={guard();Unit},personal=false,partial={part->mutable.update{it.copy(catalog=part)}})
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
        run {_,guard->
            val page=tracks({offset->api.playlistPage(guard(),list,offset)},guard,true)
            guard();mutable.update{it.copy(playlistTracks=page,playlistComplete=true)}
        }
    }
    fun download(positions: Set<Int>?=null) {
        val before=state.value;val user=before.user?:return;val page=before.page?:return
        if(!before.complete||before.busy||positions?.isEmpty()==true)return
        val selected=positions?:page.tracks.indices.map{it+1}.toSet()
        if(selected.isEmpty())return
        run(preparing=true){_,guard->withContext(Dispatchers.IO){
            val queue=AudioExportRuntime.state.value
            musicCheck(!blocked()&&!queue.unfinished&&queue.status !in setOf("RUNNING","QUEUED"),MusicError.BUSY)
            val proofAccess=guard();val viewer=api.accountOwner(proofAccess);guard()
            musicCheck(proofAccess.accountId==null||proofAccess.accountId==viewer,MusicError.ACCOUNT_CHANGED)
            mutable.update{it.copy(viewerAccount=viewer)}
            val list=before.playlist?.let{old->
                val current=MusicCatalog.load(api,guard(),user.id,guard={guard();Unit},personal=false)
                musicCheck(current.complete,MusicError.INCOMPLETE)
                current.lists.singleOrNull{it.key==old.key}?:throw MusicFailure(MusicError.UNAVAILABLE)
            }
            val current=MusicPagination.tracks({offset->if(list==null)api.libraryPage(guard(),user.id,offset)else api.playlistPage(guard(),list,offset)})
            musicCheck(current.count==page.count&&selected.all{it>0&&it<=page.tracks.size&&current.tracks.getOrNull(it-1)?.id==page.tracks[it-1].id},MusicError.LIBRARY_CHANGED)
            val tasks=FriendMusicExport.tasks(current,user,viewer,list).filter{it.position in selected}
            guard();musicCheck(!blocked(),MusicError.BUSY)
            AudioExportRuntime.replace(application,tasks,account=viewer)
            guard();queueStart()
            withContext(Dispatchers.Main){mutable.update{it.copy(selecting=false,selection=emptySet(),message="Очередь готова. Скачивание продолжается в фоне.")};onDownloads()}
        }}
    }
}
