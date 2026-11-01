package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only fixed codes cross the diagnostics boundary. Response text and exception messages never do. */
@Serializable internal enum class MusicError(val label: String) {
    ACCESS("ВК отклонил токен. Нужен действующий пользовательский доступ к музыке."),
    DENIED("Этому доступу ВК не разрешил музыкальный метод."),
    CAPTCHA("ВК запросил капчу. Заверши проверку в своём клиенте ВК и повтори."),
    VALIDATION("ВК запросил подтверждение входа. Заверши его в своём клиенте ВК."),
    RATE_LIMIT("ВК ограничил запросы. Подожди и повтори позже."),
    METHOD("Этот музыкальный метод недоступен для данного доступа ВК."),
    HTTP("Сервер вернул ошибку HTTP."), NETWORK("Не удалось получить данные по сети. Сохрани диагностический JSON этой попытки."),
    TIMEOUT("Операция превысила допустимое время. Готовый файл не создан."),
    LIBRARY_CHANGED("Список ВК изменился во время чтения. Обнови его и повтори."),
    TLS("Не удалось проверить защищённое соединение."), TOO_LARGE("Ответ или аудиофайл превышает предел прототипа."),
    RESPONSE("ВК вернул неизвестный формат данных. Поделись JSON-отчётом этой попытки."),
    INVALID_ACCESS("Нужен только токен ВК длиной 32–4096 символов и корректный User-Agent, без адреса страницы."),
    MEDIA_URL("Адрес медиапотока не относится к поддерживаемым HTTPS-серверам ВК."),
    UNAVAILABLE("Трек недоступен для скачивания при текущем доступе ВК."),
    HLS("Этот вариант HLS пока не поддерживается. Готовый файл не создан."),
    INCOMPLETE("Загрузка или проверка длительности не завершена. Готовый файл не создан."),
    PLAYLIST_CHANGED("Сохранённый M3U8 изменён или недоступен. Аудиофайлы сохранены. Пересоздай затронутую подборку или восстанови её файл порядка."),
    FORMAT("Формат аудио пока не поддерживается. Готовый файл не создан."),
    COLLECTION_TYPE("ВК не подтвердил тип подборки."),
    ACCOUNT_CHANGED("Очередь создана для другого аккаунта ВК. Подключи её аккаунт или создай новую очередь; готовые файлы сохранены."),
    QUEUE_ACCOUNT("Эта очередь создана старой версией. Подтверди её привязку к текущему аккаунту перед продолжением."),
    STORAGE("Не удалось сохранить данные на устройстве."), BUSY("Другая музыкальная операция ещё выполняется.")
}
internal enum class MusicFailureKind { DNS, CONNECT, SOCKET, TIMEOUT, TLS, PROTOCOL, EOF, IO, MEDIA_PARSE, OTHER }
internal open class MusicFailure(val code: MusicError,val remoteCode: Int? = null,val kind: MusicFailureKind? = null,open val httpStatus: Int? = null) : Exception(code.name)
internal class NativeDataFailure(val field: String,val position: Int?=null): MusicFailure(MusicError.RESPONSE) {
    fun atOffset(offset: Int)=NativeDataFailure(field,position?.let{offset+it})
}
internal fun musicCheck(ok: Boolean, error: MusicError = MusicError.RESPONSE) { if (!ok) throw MusicFailure(error) }
/** Not a data class: accidental toString must not reveal a credential. */
internal class MusicAccess(val token: String,val agent: String,val expiresAtMs: Long?=null,
    val renewal: WebRenewal?=null,val lastVerifiedAtMs: Long?=null,val renewalBlocked: Boolean=false,
    val credentialId: String=java.util.UUID.randomUUID().toString(),val renewalOwner: Long?=null,val persisted: Boolean=false,val rejected: Boolean=false,
    val accountId: Long?=renewal?.owner?:renewalOwner,val readSessionId: String=credentialId) {
    fun withSession(renewal: WebRenewal?=this.renewal,verifiedAt: Long?=lastVerifiedAtMs,blocked: Boolean=renewalBlocked,rejected: Boolean=this.rejected,
        accountId: Long?=this.accountId,readSessionId: String=this.readSessionId)=
        MusicAccess(token,agent,expiresAtMs,renewal,verifiedAt,blocked,credentialId,renewalOwner,persisted,rejected,accountId,readSessionId)
    override fun toString() = "MusicAccess([redacted])"
}

@Serializable internal data class MusicAlbum(val id: String, val title: String)
@Serializable internal data class NativeTrack(val id: String, val title: String, val artist: String,
    val duration: Int, val restricted: Boolean = false, val album: MusicAlbum? = null,val subtitle: String?=null,val metadataError: String?=null) {
    val displayTitle get()=subtitle?.trim()?.takeIf{it.isNotEmpty()}?.let {
        if(title.endsWith(" ($it)")||title.endsWith(" $it")||title==it)title else "$title ($it)"
    }?:title
}
internal class NativePlaylist(val owner: Long, val id: Long, val title: String, val count: Int,
    val accessKey: String?, val originalOwner: Long?, val originalId: Long?, val nativeType: Int?,
    val kind: PlaylistKind=when(nativeType){0->PlaylistKind.PLAYLIST;1->PlaylistKind.ALBUM;else->PlaylistKind.UNKNOWN},val following: Boolean?=null,val deleted: Boolean=false,val photo: String?=null,val typeConflict: Boolean=false) {
    fun classified(value: PlaylistKind)=if(value==kind)this else NativePlaylist(owner,id,title,count,accessKey,originalOwner,originalId,nativeType,value,following,deleted,photo,typeConflict)
    fun reconciled(other: NativePlaylist): NativePlaylist {
        val conflict=typeConflict||other.typeConflict||(kind!=PlaylistKind.UNKNOWN&&other.kind!=PlaylistKind.UNKNOWN&&kind!=other.kind)
        val resolved=if(conflict)PlaylistKind.UNKNOWN else if(kind==PlaylistKind.UNKNOWN)other.kind else kind
        return NativePlaylist(owner,id,title,maxOf(count,other.count),accessKey?:other.accessKey,originalOwner?:other.originalOwner,originalId?:other.originalId,
            nativeType,resolved,following?:other.following,deleted||other.deleted,photo?:other.photo,conflict)
    }
    val key get() = "playlist:$owner:$id"
    override fun toString() = "NativePlaylist([metadata omitted])"
}
internal data class NativePage(val tracks: List<NativeTrack>, val count: Int)
internal data class NativeCatalog(val lists: List<NativePlaylist>, val count: Int,val complete: Boolean=lists.size==count,val nextFrom: String?=null,val cursorBased: Boolean=false) {
    override fun toString()="NativeCatalog([metadata omitted])"
}
internal data class NativeProbe(val owner: Long, val library: NativePage)
@Serializable internal enum class PlaylistKind(val folder: String) { PLAYLIST("Плейлисты"), ALBUM("Альбомы"), UNKNOWN("Подборки") }

internal object NativeProtocol {
    const val DEFAULT_AGENT = "VK-Music-Export/0.8 (Android; personal audio access probe)"
    const val LIMIT = 100
    val methods = setOf("friends.get", "users.get", "audio.get", "audio.getPlaylists", "audio.getById", "audio.delete", "audio.search",
        "catalog.getAudio","catalog.getAudioArtist","catalog.getSection","catalog.getBlockItems","audio.getAudiosByArtist")
    fun validAccess(token: String, agent: String) = token.length in 32..4096 &&
        listOf("github_pat_","ghp_","gho_","ghu_","ghs_","ghr_").none{token.startsWith(it)} &&
        token.all { it.isLetterOrDigit() && it.code < 128 || it in "_.-" } &&
        agent.length in 1..512 && agent.all { it.code in 32..126 } && agent.isNotBlank()

    fun error(code: Int) = when (code) {
        5, 27, 28 -> MusicError.ACCESS
        6, 9, 29 -> MusicError.RATE_LIMIT
        14 -> MusicError.CAPTCHA
        17 -> MusicError.VALIDATION
        7, 15, 20, 201, 203 -> MusicError.DENIED
        3 -> MusicError.METHOD
        else -> MusicError.RESPONSE
    }
    private inline fun <T> parse(block: ()->T): T = try {block()}catch(e: MusicFailure){throw e}catch(_: Exception){throw MusicFailure(MusicError.RESPONSE)}
    fun boundedObject(raw: String): JsonObject = parse {
        // Bound recursion before the JSON parser. Never retain the raw response.
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else if (c == '"') quoted = true
            else if (c == '{' || c == '[') { depth++; musicCheck(depth <= 32) }
            else if (c == '}' || c == ']') depth--
        }
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { throw MusicFailure(MusicError.RESPONSE) }
    }
    fun envelope(raw: String): JsonElement = parse {
        val root = boundedObject(raw)
        root["error"]?.let { e ->
            val code=(e as? JsonObject)?.get("error_code")?.jsonPrimitive?.intOrNull?:-1
            throw MusicFailure(error(code),code)
        }
        return root["response"] ?: throw MusicFailure(MusicError.RESPONSE)
    }
    private fun long(o: JsonObject, key: String): Long {
        val p = o[key] as? JsonPrimitive
        if(p==null||p.isString)throw NativeDataFailure(key)
        return p.longOrNull ?: throw NativeDataFailure(key)
    }
    private fun count(o: JsonObject): Int {
        val n = long(o, "count"); if(n !in 0..1_000_000)throw NativeDataFailure("count"); return n.toInt()
    }
    private fun text(o: JsonObject, key: String): String {
        val p = o[key] as? JsonPrimitive
        if(p?.isString!=true)throw NativeDataFailure(key)
        val value = p.content.replace(Regex("[\\r\\n\\t]+")," ").trim()
        if(value.isBlank()||value.length>500||value.any{it<' '||it=='\u007f'})throw NativeDataFailure(key)
        return value
    }
    fun owner(response: JsonElement): Long = parse {
        val a = response as? JsonArray; musicCheck(a?.size == 1)
        val id = long(a!![0].jsonObject, "id"); musicCheck(id > 0); return id
    }
    private fun track(o: JsonObject,warning:(String)->Unit={}): NativeTrack {
        val owner = long(o, "owner_id"); val id = long(o, "id")
        if(owner==0L)throw NativeDataFailure("owner_id")
        if(id<=0)throw NativeDataFailure("id")
        val duration = long(o, "duration"); if(duration !in 0..Int.MAX_VALUE.toLong())throw NativeDataFailure("duration")
        val albumValue=o["album"]?.takeUnless{it is JsonNull}
        val album=albumValue?.let { raw->
            // An incomplete optional release descriptor must not discard the audio or its position.
            try {
                val a=raw as? JsonObject?:throw NativeDataFailure("album")
                val albumId=long(a,"id");if(albumId<=0)throw NativeDataFailure("album.id")
                val albumOwner=(a["owner_id"] as? JsonPrimitive)?.longOrNull?.takeIf{it!=0L}
                MusicAlbum(if(albumOwner==null)"album:$albumId" else "${albumOwner}_$albumId",text(a,"title"))
            }catch(_: MusicFailure){warning("album");null}
        }
        // URL availability is a separate fact; never filter the position out of the library.
        val restriction=o["is_restricted"]?.takeUnless{it is JsonNull}
        val restricted=when(restriction) {
            null->false
            is JsonPrimitive->restriction.booleanOrNull?:when(restriction.intOrNull){0->false;1->true;else->throw NativeDataFailure("is_restricted")}
            else->throw NativeDataFailure("is_restricted")
        }
        val subtitle=o["subtitle"]?.takeUnless{it is JsonNull}?.let {
            val p=it as? JsonPrimitive;if(p?.isString!=true)throw NativeDataFailure("subtitle")
            if(p.content.length>500||p.content.any{c->c<' '||c=='\u007f'})throw NativeDataFailure("subtitle")
            if(p.content.isBlank())null else text(o,"subtitle")
        }
        return NativeTrack("${owner}_$id", text(o,"title"), text(o,"artist"), duration.toInt(), restricted, album,subtitle)
    }
    fun page(response: JsonElement,warning:(String,Int)->Unit={_,_->}): NativePage = parse {
        val o = response as? JsonObject ?: throw MusicFailure(MusicError.RESPONSE)
        val total = count(o); val items = o["items"] as? JsonArray ?: throw MusicFailure(MusicError.RESPONSE)
        musicCheck(items.size <= LIMIT && items.size <= total)
        return NativePage(items.mapIndexed { index,row->
            try{track(row as? JsonObject?:throw NativeDataFailure("items")){warning(it,index+1)}}
            catch(e: NativeDataFailure){
                val o=row as? JsonObject?:throw NativeDataFailure("items",index+1)
                // Keep a known VK identity and its original position. Never invent an ID or export guessed metadata.
                val owner=try{long(o,"owner_id").also{if(it==0L)throw NativeDataFailure("owner_id")}}catch(f: NativeDataFailure){throw NativeDataFailure(f.field,index+1)}
                val id=try{long(o,"id").also{if(it<=0)throw NativeDataFailure("id")}}catch(f: NativeDataFailure){throw NativeDataFailure(f.field,index+1)}
                warning(e.field,index+1)
                val duration=(o["duration"] as? JsonPrimitive)?.takeIf{!it.isString}?.longOrNull?.takeIf{it in 0..Int.MAX_VALUE.toLong()}?.toInt()?:0
                NativeTrack("${owner}_$id",runCatching{text(o,"title")}.getOrDefault("Запись с ошибкой данных"),
                    runCatching{text(o,"artist")}.getOrDefault("Описание недоступно"),duration,restricted=true,metadataError=e.field)
            }
        }, total)
    }
    fun catalog(response: JsonElement): NativeCatalog = parse {
        val o = response as? JsonObject ?: throw MusicFailure(MusicError.RESPONSE)
        val total = count(o); val items = o["items"] as? JsonArray ?: throw MusicFailure(MusicError.RESPONSE)
        musicCheck(items.size <= LIMIT && items.size <= total)
        val lists = items.map { e ->
            val p = e.jsonObject; val owner = long(p,"owner_id"); val id = long(p,"id")
            musicCheck(owner != 0L && id > 0)
            val accessKey = (p["access_key"] as? JsonPrimitive)?.takeIf{it.isString}?.content?.also {
                musicCheck(it.length <= 512 && it.all { c -> c.code in 33..126 })
            }
            val original = p["original"] as? JsonObject
            val originalOwner=(original?.get("owner_id") as? JsonPrimitive)?.longOrNull?.takeIf{it!=0L}
            val originalId=((original?.get("playlist_id")?:original?.get("id")) as? JsonPrimitive)?.longOrNull?.takeIf{it>0}
            val explicitKind=when((p["type"] as? JsonPrimitive)?.content) {
                "album","1"->PlaylistKind.ALBUM;"playlist","0"->PlaylistKind.PLAYLIST;else->PlaylistKind.UNKNOWN
            }
            val releaseKind=when((p["album_type"] as? JsonPrimitive)?.content) {
                "album","single","ep"->PlaylistKind.ALBUM;"collection"->PlaylistKind.PLAYLIST;else->PlaylistKind.UNKNOWN
            }
            val conflict=explicitKind!=PlaylistKind.UNKNOWN&&releaseKind!=PlaylistKind.UNKNOWN&&explicitKind!=releaseKind
            val kind=when {
                conflict->PlaylistKind.UNKNOWN
                explicitKind!=PlaylistKind.UNKNOWN->explicitKind
                else->releaseKind
            }
            NativePlaylist(owner,id,text(p,"title"),count(p),accessKey,
                originalOwner.takeIf{originalId!=null}, originalId.takeIf{originalOwner!=null},
                (p["type"] as? JsonPrimitive)?.intOrNull,kind,
                flag(p,"is_following"),flag(p,"is_deleted")==true,MusicArtists.photo(p["photo"]?:p["thumb"]?:p["thumbs"]),conflict)
        }
        val cursorValue=o["next_from"]
        if(cursorValue!=null&&cursorValue !is JsonNull&&(cursorValue !is JsonPrimitive||!cursorValue.isString))throw NativeDataFailure("next_from")
        val next=(cursorValue as? JsonPrimitive)?.takeIf{it.isString}?.content?.takeIf{it.isNotEmpty()}
        if(next!=null&&(next.length>8192||next.any{it<' '||it=='\u007f'}))throw NativeDataFailure("next_from")
        return NativeCatalog(lists,total,nextFrom=next,cursorBased="next_from" in o)
    }
    private fun flag(o: JsonObject,key: String): Boolean? = (o[key] as? JsonPrimitive)?.let{it.booleanOrNull?:it.intOrNull?.let{n->when(n){0->false;1->true;else->null}}}
    private fun artworkMatch(o: JsonObject): ArtworkMatch {
        val t=track(o)
        return try{ArtworkMatch(t,artworkCandidates(JsonArray(listOf(o)),t))}
        catch(e: MusicFailure){ArtworkMatch(t,emptyList(),e.code)}
    }
    fun artworkSearch(response: JsonElement,offset: Int): ArtworkMatches = parse {
        val o=response as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
        val total=count(o);val items=o["items"] as? JsonArray?:throw MusicFailure(MusicError.RESPONSE)
        musicCheck(items.size<=ArtworkQuery.PAGE_SIZE&&items.size<=total&&offset+items.size<=total,MusicError.RESPONSE)
        val matches=items.map{artworkMatch(it.jsonObject)}
        musicCheck(matches.map{it.track.id}.distinct().size==matches.size,MusicError.RESPONSE)
        ArtworkMatches(matches,total,offset)
    }
    fun metadataSearch(response: JsonElement,offset: Int): MetadataMatches = parse {
        val page=page(response)
        musicCheck(page.tracks.size<=ArtworkQuery.PAGE_SIZE&&offset+page.tracks.size<=page.count&&page.tracks.map{it.id}.distinct().size==page.tracks.size)
        MetadataMatches(page.tracks,page.count,offset)
    }
    fun artworkRecord(response: JsonElement,id: String): ArtworkMatch? = parse {
        val items=response as? JsonArray?:throw MusicFailure(MusicError.RESPONSE)
        if(items.isEmpty())return@parse null
        musicCheck(items.size==1,MusicError.RESPONSE)
        artworkMatch(items[0].jsonObject).also{musicCheck(it.track.id==id,MusicError.RESPONSE)}
    }
    /** Metadata lookup does not require an audio URL or a cover, or membership in My Music. */
    fun metadataRecord(response: JsonElement,id: String): NativeTrack? = parse {
        val items=response as? JsonArray?:throw MusicFailure(MusicError.RESPONSE)
        if(items.isEmpty())return@parse null
        musicCheck(items.size==1)
        track(items[0].jsonObject).also{musicCheck(it.id==id)}
    }
    fun artwork(response: JsonElement,expected: NativeTrack): String? = artworkCandidates(response,expected).firstOrNull()?.url
    fun artworkCandidates(response: JsonElement,expected: NativeTrack): List<ArtworkCandidate> = parse {
        val items=response as? JsonArray?:throw MusicFailure(MusicError.RESPONSE)
        if(items.isEmpty())throw MusicFailure(MusicError.UNAVAILABLE)
        musicCheck(items.size==1)
        val o=items[0].jsonObject;val actual=track(o)
        musicCheck(actual.id==expected.id&&actual.duration==expected.duration)
        val album=o["album"] as? JsonObject
        val thumbs=listOfNotNull((o["thumb"] as? JsonObject)?.let{ArtworkOrigin.TRACK to it},
            (album?.get("thumb") as? JsonObject)?.let{ArtworkOrigin.ALBUM to it})
        val candidates=mutableListOf<ArtworkCandidate>()
        var offered=false
        for((origin,thumb)in thumbs)for(size in listOf(1200,600,300,270,135,68,34)) {
            val url=(thumb["photo_$size"] as? JsonPrimitive)?.takeIf{it.isString}?.content?.takeIf{it.isNotBlank()}?:continue
            offered=true
            val safe=runCatching{ArtworkAddress.require(url).toString()}.getOrNull()
            if(safe!=null)candidates.add(ArtworkCandidate(safe,origin,size))
        }
        if(offered&&candidates.isEmpty())throw MusicFailure(MusicError.MEDIA_URL)
        candidates.distinctBy{it.url}
    }
    fun media(response: JsonElement,expected: NativeTrack): String=resolveMedia(response,expected).url
    fun resolveMedia(response: JsonElement,expected: NativeTrack): ResolvedAudio = parse {
        val items=response as? JsonArray?:throw MusicFailure(MusicError.RESPONSE)
        if(items.isEmpty())throw TrackUnavailable(TrackAccessIssue.NOT_RETURNED)
        musicCheck(items.size==1)
        val o=items[0].jsonObject;val actual=track(o)
        musicCheck(actual.id==expected.id&&(expected.duration<=0||actual.duration==expected.duration))
        if(actual.restricted)throw TrackUnavailable(TrackAccessIssue.RESTRICTED)
        if(actual.duration<=0)throw TrackUnavailable(TrackAccessIssue.NO_DURATION)
        val url=(o["url"] as? JsonPrimitive)?.takeIf{it.isString}?.content?.takeIf{it.isNotBlank()}
            ?:throw TrackUnavailable(TrackAccessIssue.NO_STREAM)
        ResolvedAudio(if("subtitle" in o)actual else actual.copy(subtitle=expected.subtitle),MediaAddress.require(url).toString())
    }

}

/** A CDN request never inherits API authorization, cookies, referrer or the client's custom UA. */
internal object MediaAddress {
    private val domains = setOf("userapi.com","vkuseraudio.net","vk-cdn.net","vkuser.net","vkcdn.net","okcdn.ru","mycdn.me")
    fun require(raw: String): HttpUrl {
        musicCheck(raw.length in 1..8192 && raw.none { it <= ' ' || it == '\\' || it == '\u007f' },MusicError.MEDIA_URL)
        val u = raw.toHttpUrlOrNull() ?: throw MusicFailure(MusicError.MEDIA_URL)
        musicCheck(u.scheme == "https" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.fragment == null &&
            domains.any { u.host == it || u.host.endsWith(".$it") },MusicError.MEDIA_URL)
        return u
    }
    fun resolve(base: String, part: String): String {
        val url = require(base).resolve(part) ?: throw MusicFailure(MusicError.MEDIA_URL)
        return require(url.toString()).toString()
    }
}
