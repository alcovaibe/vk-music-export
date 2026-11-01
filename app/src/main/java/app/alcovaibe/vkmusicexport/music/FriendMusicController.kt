package app.alcovaibe.vkmusicexport.music

import android.app.Application
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Read-only remote profiles. File publication belongs to the existing persisted download job. */
internal class FriendMusicController(private val application: Application,private val api: MusicApi,private val scope: CoroutineScope,
    private val onDownloads: ()->Unit={},private val queueStart: suspend()->Unit={AudioExportRuntime.start(application)},
    private val blocked: ()->Boolean={false}) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application)else null
    private val mutable=MutableStateFlow(FriendMusicState());val state=mutable.asStateFlow()
    private var work: Job?=null;private var generation=0;private var lease: MusicAccess?=null
    fun reset(){generation++;work?.cancel();work=null;lease=null;mutable.value=FriendMusicState()}
    fun pauseRead(){generation++;work?.cancel();work=null;mutable.update{it.copy(busy=false,preparing=false,selecting=false,selection=emptySet())}}
    fun back() {
        if(state.value.selecting){mutable.update{it.copy(selecting=false,selection=emptySet())};return}
        pauseRead()
        mutable.update{if(it.playlist!=null)it.copy(playlist=null,playlistTracks=null,playlistComplete=false,playlistProblem=null,musicQuery="",message=null)
            else it.copy(user=null,tracks=null,tracksComplete=false,catalog=null,problems=emptyMap(),musicQuery="",message=null)}
    }
    fun friendsQuery(value: String){mutable.update{it.copy(friendsQuery=value.take(200))}}
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
             catch(e: MusicReadChanged){if(g==generation){lease=null;mutable.value=FriendMusicState(listProblem=FriendMusicProblem(MusicError.ACCOUNT_CHANGED))}}
             catch(e: Exception){if(g==generation)problem(FriendMusicProblem.from(e))}
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
        mutable.update{it.copy(user=null,friends=emptyList(),friendsComplete=false,friendsTotal=null,listProblem=null)}
        run {_,guard->
            val rows=mutableListOf<MusicFriend>();val seen=mutableSetOf<Long>();var total: Int?=null
            do {
                val page=api.friendsPage(guard(),rows.size);guard()
                musicCheck(total==null||total==page.count,MusicError.LIBRARY_CHANGED);total=page.count
                musicCheck(rows.size+page.items.size<=page.count,MusicError.LIBRARY_CHANGED)
                musicCheck(page.items.isNotEmpty()||rows.size==page.count,MusicError.INCOMPLETE)
                page.items.forEach{musicCheck(seen.add(it.id),MusicError.INCOMPLETE)};rows.addAll(page.items)
                mutable.update{it.copy(friends=rows.toList(),friendsTotal=page.count)}
            }while(rows.size<total!!)
            mutable.update{it.copy(friendsComplete=true)}
            Diagnostics.event("music_friends_loaded",mapOf("count" to rows.size))
        }
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
        run {_,guard->
            val fresh=api.friendProfile(guard(),user.id);guard();mutable.update{it.copy(user=fresh)}
            try {
                val page=tracks({offset->api.libraryPage(guard(),user.id,offset)},guard,false)
                guard();mutable.update{it.copy(tracks=page,tracksComplete=true)}
            }catch(e: CancellationException){throw e}catch(e: MusicReadChanged){throw e}catch(e: Exception){mutable.update{it.copy(problems=it.problems+(FriendMusicTab.TRACKS to FriendMusicProblem.from(e)))}}
            try {
                val catalog=MusicCatalog.load(api,guard(),user.id,guard={guard();Unit},personal=false)
                guard();mutable.update{it.copy(catalog=catalog)}
                if(!catalog.complete)mutable.update{it.copy(problems=it.problems+listOf(FriendMusicTab.PLAYLISTS,FriendMusicTab.ALBUMS).associateWith{FriendMusicProblem(MusicError.INCOMPLETE)})}
            }catch(e: CancellationException){throw e}catch(e: MusicReadChanged){throw e}catch(e: Exception){mutable.update{it.copy(problems=it.problems+listOf(FriendMusicTab.PLAYLISTS,FriendMusicTab.ALBUMS).associateWith{FriendMusicProblem.from(e)})}}
        }
    }
    fun playlist(list: NativePlaylist) {
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
