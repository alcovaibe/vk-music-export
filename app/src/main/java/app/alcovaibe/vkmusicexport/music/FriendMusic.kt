package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.*

internal data class MusicFriend(val id: Long,val firstName: String,val lastName: String,val handle: String?=null,
    val photo: String?=null,val closed: Boolean=false,val accessible: Boolean=true) {
    val name get()=listOf(firstName,lastName).filter{it.isNotBlank()}.joinToString(" ")
    val vkUrl get()="https://vk.com/id$id"
}
internal data class FriendsPage(val items: List<MusicFriend>,val count: Int)
internal enum class FriendMusicTab(val title: String) { TRACKS("Треки"),PLAYLISTS("Плейлисты"),ALBUMS("Альбомы") }
internal data class FriendMusicProblem(val code: MusicError,val remoteCode: Int?=null,val httpStatus: Int?=null) {
    val description get()=when {
        remoteCode==201->"ВК запретил доступ к аудиозаписям этого профиля."
        code==MusicError.DENIED->"ВК не разрешил этому подключению получить данный музыкальный раздел."
        code==MusicError.METHOD->"Этот метод недоступен текущему подключению ВК."
        code==MusicError.ACCESS||code==MusicError.INVALID_ACCESS->"Нужно подключить ВК заново."
        code==MusicError.TIMEOUT->"ВК не завершил загрузку вовремя. Уже полученные данные показаны."
        code==MusicError.NETWORK||code==MusicError.TLS||code==MusicError.HTTP->"Не удалось получить данные по сети. Повтори попытку."
        code==MusicError.ACCOUNT_CHANGED->"Подключение ВК изменилось. Обнови список друзей."
        else->code.label
    }+(remoteCode?.let{" Код API ВК: $it."}?:httpStatus?.let{" HTTP $it."}.orEmpty())
    companion object {fun from(e: Exception)=(e as? MusicFailure)?.let{FriendMusicProblem(it.code,it.remoteCode,it.httpStatus)}?:FriendMusicProblem(MusicError.RESPONSE)}
}
internal data class FriendMusicState(val friends: List<MusicFriend> = emptyList(),val friendsTotal: Int?=null,val friendsComplete: Boolean=false,
    val busy: Boolean=false,val preparing: Boolean=false,val friendsQuery: String="",val musicQuery: String="",
    val user: MusicFriend?=null,val tab: FriendMusicTab=FriendMusicTab.TRACKS,val tracks: NativePage?=null,val tracksComplete: Boolean=false,
    val catalog: NativeCatalog?=null,val playlist: NativePlaylist?=null,val playlistTracks: NativePage?=null,val playlistComplete: Boolean=false,
    val problems: Map<FriendMusicTab,FriendMusicProblem> = emptyMap(),val listProblem: FriendMusicProblem?=null,val playlistProblem: FriendMusicProblem?=null,
    val selecting: Boolean=false,val selection: Set<Int> = emptySet(),val message: String?=null) {
    val page get()=if(playlist!=null)playlistTracks else tracks
    val complete get()=if(playlist!=null)playlistComplete else tracksComplete
    fun collections()=catalog?.lists.orEmpty().filter{it.kind==if(tab==FriendMusicTab.ALBUMS)PlaylistKind.ALBUM else PlaylistKind.PLAYLIST}
    fun count(tab: FriendMusicTab): Int?=when(tab) {
        FriendMusicTab.TRACKS->tracks?.count
        else->catalog?.takeIf{it.complete}?.lists?.count{it.kind==if(tab==FriendMusicTab.ALBUMS)PlaylistKind.ALBUM else PlaylistKind.PLAYLIST}
    }
}
internal object FriendMusicProtocol {
    private fun text(o: JsonObject,key: String,optional: Boolean=false): String {
        val value=(o[key] as? JsonPrimitive)?.contentOrNull
        if(optional&&value==null)return ""
        musicCheck(value!=null&&value.length<=300&&value.none{it<' '||it=='\u007f'})
        return value!!.trim()
    }
    private fun flag(o: JsonObject,key: String,default: Boolean)=
        (o[key] as? JsonPrimitive)?.let{it.booleanOrNull?:it.intOrNull?.let{n->when(n){0->false;1->true;else->null}}}?:default
    fun user(value: JsonElement): MusicFriend {
        val o=value as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
        val id=(o["id"] as? JsonPrimitive)?.longOrNull?:throw MusicFailure(MusicError.RESPONSE);musicCheck(id>0)
        val first=text(o,"first_name");val last=text(o,"last_name",true);musicCheck(first.isNotBlank()||last.isNotBlank())
        val domain=listOf("domain","screen_name").firstNotNullOfOrNull{key->
            (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf{Regex("[A-Za-z0-9_.]{1,100}").matches(it)}
        }
        return MusicFriend(id,first,last,domain?.let{"@$it"},MusicArtists.photo(o["photo_200"]?:o["photo_100"]),
            flag(o,"is_closed",false),flag(o,"can_access_closed",true))
    }
    fun page(value: JsonElement): FriendsPage {
        val o=value as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
        val count=(o["count"] as? JsonPrimitive)?.intOrNull?:throw MusicFailure(MusicError.RESPONSE)
        val items=o["items"] as? JsonArray?:throw MusicFailure(MusicError.RESPONSE)
        musicCheck(count in 0..20000&&items.size<=NativeProtocol.LIMIT&&items.size<=count)
        return FriendsPage(items.map(::user),count)
    }
    fun profile(value: JsonElement,expected: Long): MusicFriend {
        val rows=value as? JsonArray?:throw MusicFailure(MusicError.RESPONSE);musicCheck(rows.size==1)
        return user(rows.single()).also{musicCheck(it.id==expected,MusicError.RESPONSE)}
    }
}

/** Source account remains the viewer; the friend and collection identify a separate source. */
internal object FriendMusicExport {
    fun tasks(page: NativePage,user: MusicFriend,viewer: Long,list: NativePlaylist?=null): List<AudioExportTask> {
        musicCheck(viewer>0&&user.id>0,MusicError.ACCOUNT_CHANGED)
        val source=ExportSource(viewer,if(list==null)"friend_library" else "friend_collection",
            if(list==null)user.id.toString() else "${user.id}:${list.key}")
        val folder=ExportLayout.component(user.name,120)+" [${user.id}]"
        val folders=listOf("Друзья",folder)+(list?.let{listOf(ExportLayout.component(
            (if(it.kind==PlaylistKind.ALBUM)"Альбом — " else "Плейлист — ")+it.title,120)+" [${it.owner}_${it.id}]")}?:emptyList())
        val occurrences=mutableMapOf<String,Int>()
        return page.tracks.mapIndexed{index,track->
            val occurrence=(occurrences[track.id]?:0)+1;occurrences[track.id]=occurrence
            ExportLayout.task(track,folders,index+1,source,occurrence)
        }
    }
}
