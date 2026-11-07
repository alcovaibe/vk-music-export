package app.alcovaibe.vkmusicexport.music

import android.app.Application
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Read-only remote profiles. File publication belongs to the existing persisted download job. */
internal class FriendMusicController(private val application: Application,private val api: MusicApi,private val scope: CoroutineScope,
    private val onDownloads: ()->Unit={},private val queueStart: suspend()->Unit={AudioExportRuntime.start(application)},
    clock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val blocked: ()->Boolean={false},previewBlocked: ()->Boolean=blocked) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application)else null
    private val mutable=MutableStateFlow(FriendMusicState());val state=mutable.asStateFlow()
    private var work: Job?=null;private var generation=0;private var lease: MusicAccess?=null
    private val availabilityCache=FriendAvailabilityCache()
    private val exportReads=ExportReadCache(clock)
    private val sourceReads=MusicSourceReader(api,exportReads)
    private var foreground=false
    private var previewVisible=false
    private val previews=FriendAvailabilityReader(scope,api,availabilityCache,::checked,previewBlocked,
        publish={id,status->mutable.update{state->if(state.friends.any{it.id==id})
            state.copy(friendAccess=state.friendAccess+(id to status))else state}},
        changedSession={availabilityCache.clear();exportReads.clear();lease=null
            mutable.update{it.copy(friendAccess=emptyMap(),listProblem=FriendMusicProblem(MusicError.ACCOUNT_CHANGED))}},
        report={issue->Diagnostics.event("music_friend_access_preview",mapOf("reason" to issue.code.name,"vk_code" to issue.remoteCode))})
    private fun updatePreviews(){previews.setActive(foreground&&previewVisible&&state.value.user==null)}
    fun setForeground(value: Boolean){foreground=value;updatePreviews()}
    fun setPreviewVisible(value: Boolean){previewVisible=value;updatePreviews()}
    fun reset(){
        generation++;work?.cancel();work=null;lease=null
        previews.clear();exportReads.clear();mutable.value=FriendMusicState()
    }
    fun pauseRead(){
        generation++;work?.cancel();work=null;previews.setActive(false)
        mutable.update{it.copy(busy=false,preparing=false,selecting=false,selection=emptySet())}
    }
    fun back() {
        if(state.value.selecting){mutable.update{it.copy(selecting=false,selection=emptySet())};return}
        pauseRead()
        mutable.update{if(it.playlist!=null)it.copy(playlist=null,playlistTracks=null,playlistComplete=false,playlistProblem=null,musicQuery="",message=null)
            else it.copy(user=null,tracks=null,tracksComplete=false,catalog=null,problems=emptyMap(),musicQuery="",message=null)}
        updatePreviews()
    }
    fun prioritizeVisible(ids: List<Long>){
        val known=state.value.friends.mapTo(hashSetOf()){it.id}
        previews.visible(ids.filter{it in known});updatePreviews()
    }
    fun friendsQuery(value: String){mutable.update{it.copy(friendsQuery=value.take(200))}}
    fun musicQuery(value: String){mutable.update{it.copy(musicQuery=value.take(200))}}
    fun tab(value: FriendMusicTab){mutable.update{it.copy(tab=value,playlist=null,playlistTracks=null,playlistComplete=false,playlistProblem=null,selecting=false,selection=emptySet(),musicQuery="",message=null)}}
    fun selectMode(){mutable.update{it.copy(selecting=!it.selecting,selection=emptySet())}}
    fun toggle(position: Int){mutable.update{if(position in 1..(it.page?.tracks?.size?:0))it.copy(selecting=true,selection=if(position in it.selection)it.selection-position else it.selection+position)else it}}
    fun refresh(){state.value.user?.let{open(it,force=true)}?:loadFriends(forceStatuses=true)}
    private suspend fun checked(initial: MusicAccess): MusicAccess {
        currentCoroutineContext().ensureActive()
        return (sessions?.continueRead(initial)?:withContext(Dispatchers.IO){vault.read()?:throw MusicFailure(MusicError.ACCESS)}.also{
            MusicReadIdentity(initial).require(it)
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
                 previews.clear();exportReads.clear();lease=null
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
        previews.setActive(false)
        previews.visible(emptyList())
        mutable.update{it.copy(user=null,friends=emptyList(),friendAccess=emptyMap(),
            friendsComplete=false,friendsTotal=null,listProblem=null)}
        run {_,guard->
            val rows=mutableListOf<MusicFriend>();val seen=mutableSetOf<Long>();var total: Int?=null
            var access: MusicAccess?=null
            do {
                val current=guard()
                if(access==null) {
                    access=current
                    previews.bind(current,forceStatuses)
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
                updatePreviews()
            }while(rows.size<total!!)
            mutable.update{it.copy(friendsComplete=true)}
            updatePreviews()
            Diagnostics.event("music_friends_loaded",mapOf("count" to rows.size))
        }
    }

    fun open(user: MusicFriend,force: Boolean=false) {
        mutable.update{it.copy(user=user,tab=FriendMusicTab.TRACKS,tracks=null,tracksComplete=false,catalog=null,playlist=null,playlistTracks=null,
            playlistComplete=false,problems=emptyMap(),playlistProblem=null,selecting=false,selection=emptySet(),musicQuery="")}
        updatePreviews()
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
