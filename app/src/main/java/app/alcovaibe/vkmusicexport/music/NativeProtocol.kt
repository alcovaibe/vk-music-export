package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only fixed codes cross the diagnostics boundary. Response text and exception messages never do. */
internal enum class MusicError(val label: String) {
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
    RESPONSE("ВК вернул неизвестный формат данных. Скопируй диагностический лог."),
    INVALID_ACCESS("Нужен только токен ВК длиной 32–4096 символов и корректный User-Agent, без адреса страницы."),
    MEDIA_URL("Адрес медиапотока не относится к поддерживаемым HTTPS-серверам ВК."),
    UNAVAILABLE("ВК не вернул доступную ссылку на этот трек."),
    HLS("Этот вариант HLS пока не поддерживается. Готовый файл не создан."),
    INCOMPLETE("Загрузка или проверка длительности не завершена. Готовый файл не создан."),
    FORMAT("Формат аудио пока не поддерживается. Готовый файл не создан."),
    COLLECTION_TYPE("Сначала выбери тип подборки: плейлист или альбом."),
    STORAGE("Не удалось сохранить данные на устройстве."), BUSY("Другая музыкальная операция ещё выполняется.")
}
internal enum class MusicFailureKind { DNS, CONNECT, SOCKET, TIMEOUT, TLS, PROTOCOL, EOF, IO, MEDIA_PARSE, OTHER }
internal class MusicFailure(val code: MusicError,val remoteCode: Int? = null,val kind: MusicFailureKind? = null) : Exception(code.name)
internal fun musicCheck(ok: Boolean, error: MusicError = MusicError.RESPONSE) { if (!ok) throw MusicFailure(error) }
/** Not a data class: accidental toString must not reveal a credential. */
internal class MusicAccess(val token: String,val agent: String,val expiresAtMs: Long?=null,
    val renewal: WebRenewal?=null,val lastVerifiedAtMs: Long?=null,val renewalBlocked: Boolean=false,
    val credentialId: String=java.util.UUID.randomUUID().toString(),val renewalOwner: Long?=null,val persisted: Boolean=false,val rejected: Boolean=false) {
    fun withSession(renewal: WebRenewal?=this.renewal,verifiedAt: Long?=lastVerifiedAtMs,blocked: Boolean=renewalBlocked,rejected: Boolean=this.rejected)=
        MusicAccess(token,agent,expiresAtMs,renewal,verifiedAt,blocked,credentialId,renewalOwner,persisted,rejected)
    override fun toString() = "MusicAccess([redacted])"
}

@Serializable internal data class MusicAlbum(val id: String, val title: String)
@Serializable internal data class NativeTrack(val id: String, val title: String, val artist: String,
    val duration: Int, val restricted: Boolean = false, val album: MusicAlbum? = null)
internal class NativePlaylist(val owner: Long, val id: Long, val title: String, val count: Int,
    val accessKey: String?, val originalOwner: Long?, val originalId: Long?, val nativeType: Int?,
    val kind: PlaylistKind=PlaylistKind.UNKNOWN) {
    val key get() = "playlist:$owner:$id"
    override fun toString() = "NativePlaylist([metadata omitted])"
}
internal data class NativePage(val tracks: List<NativeTrack>, val count: Int)
internal data class NativeCatalog(val lists: List<NativePlaylist>, val count: Int)
internal data class NativeProbe(val owner: Long, val library: NativePage)
internal enum class PlaylistKind(val folder: String) { PLAYLIST("Плейлисты"), ALBUM("Альбомы"), UNKNOWN("Тип не определён") }

internal object NativeProtocol {
    const val DEFAULT_AGENT = "VK-Music-Export/0.8 (Android; personal audio access probe)"
    const val LIMIT = 100
    val methods = setOf("users.get", "audio.get", "audio.getPlaylists", "audio.getById", "audio.delete")
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
        musicCheck(p != null && !p.isString)
        return p!!.longOrNull ?: throw MusicFailure(MusicError.RESPONSE)
    }
    private fun count(o: JsonObject): Int {
        val n = long(o, "count"); musicCheck(n in 0..1_000_000); return n.toInt()
    }
    private fun text(o: JsonObject, key: String): String {
        val p = o[key] as? JsonPrimitive; musicCheck(p?.isString == true)
        val value = p!!.content
        musicCheck(value.isNotBlank() && value.length <= 500 && value.none { it < ' ' || it == '\u007f' })
        return value.trim()
    }
    fun owner(response: JsonElement): Long = parse {
        val a = response as? JsonArray; musicCheck(a?.size == 1)
        val id = long(a!![0].jsonObject, "id"); musicCheck(id > 0); return id
    }
    private fun track(o: JsonObject): NativeTrack {
        val owner = long(o, "owner_id"); val id = long(o, "id")
        musicCheck(owner != 0L && id > 0)
        val duration = long(o, "duration"); musicCheck(duration in 0..86400)
        val album = (o["album"] as? JsonObject)?.let {
            val albumId=long(it,"id");musicCheck(albumId>0)
            val albumOwner=(it["owner_id"] as? JsonPrimitive)?.longOrNull
            MusicAlbum(if(albumOwner==null)"album:$albumId" else "${albumOwner}_$albumId",text(it,"title"))
        }
        // URL availability is a separate fact; never filter the position out of the library.
        val restricted = o["is_restricted"]?.jsonPrimitive?.let { it.booleanOrNull == true || it.intOrNull == 1 } ?: false
        return NativeTrack("${owner}_$id", text(o,"title"), text(o,"artist"), duration.toInt(), restricted, album)
    }
    fun page(response: JsonElement): NativePage = parse {
        val o = response as? JsonObject ?: throw MusicFailure(MusicError.RESPONSE)
        val total = count(o); val items = o["items"] as? JsonArray ?: throw MusicFailure(MusicError.RESPONSE)
        musicCheck(items.size <= LIMIT && items.size <= total)
        return NativePage(items.map { track(it.jsonObject) }, total)
    }
    fun catalog(response: JsonElement): NativeCatalog = parse {
        val o = response as? JsonObject ?: throw MusicFailure(MusicError.RESPONSE)
        val total = count(o); val items = o["items"] as? JsonArray ?: throw MusicFailure(MusicError.RESPONSE)
        musicCheck(items.size <= LIMIT && items.size <= total)
        val lists = items.map { e ->
            val p = e.jsonObject; val owner = long(p,"owner_id"); val id = long(p,"id")
            musicCheck(owner != 0L && id > 0)
            val accessKey = (p["access_key"] as? JsonPrimitive)?.content?.also {
                musicCheck(it.length <= 512 && it.all { c -> c.code in 33..126 })
            }
            val original = p["original"] as? JsonObject
            NativePlaylist(owner,id,text(p,"title"),count(p),accessKey,
                original?.let { long(it,"owner_id") }, original?.let { long(it,"playlist_id") },
                (p["type"] as? JsonPrimitive)?.intOrNull,
                when((p["type"] as? JsonPrimitive)?.takeIf{it.isString}?.content) {
                    "album"->PlaylistKind.ALBUM;"playlist"->PlaylistKind.PLAYLIST
                    else->if((p["album_type"] as? JsonPrimitive)?.content in setOf("album","single","ep"))PlaylistKind.ALBUM else PlaylistKind.UNKNOWN
                })
        }
        musicCheck(lists.map { it.key }.distinct().size == lists.size)
        return NativeCatalog(lists,total)
    }
    fun artwork(response: JsonElement,expected: NativeTrack): String? = parse {
        val items=response as? JsonArray;musicCheck(items?.size==1)
        val o=items!![0].jsonObject;val actual=track(o)
        musicCheck(actual.id==expected.id&&actual.duration==expected.duration)
        val album=o["album"] as? JsonObject
        val candidates=listOfNotNull(o["thumb"] as? JsonObject,album?.get("thumb") as? JsonObject)
        for(thumb in candidates)for(size in listOf(1200,600,300,270,135,68,34)) {
            val url=(thumb["photo_$size"] as? JsonPrimitive)?.takeIf{it.isString}?.content?.takeIf{it.isNotBlank()}?:continue
            val safe=runCatching{ArtworkAddress.require(url).toString()}.getOrNull()
            if(safe!=null)return@parse safe
        }
        null
    }
    fun media(response: JsonElement, expected: NativeTrack): String = parse {
        val items = response as? JsonArray; musicCheck(items?.size == 1)
        val o = items!![0].jsonObject; val actual = track(o)
        musicCheck(actual.id == expected.id && actual.duration == expected.duration)
        val url = (o["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: throw MusicFailure(MusicError.UNAVAILABLE)
        return MediaAddress.require(url).toString()
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
