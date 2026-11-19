package app.alcovaibe.vkmusicexport.music

import java.net.URI
import java.net.URLDecoder

internal object VkMusicLinks {
    private val hosts = setOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru", "www.vk.com", "www.vk.ru")
    private val url = Regex("https?://[^\\s<>\"«»]+", RegexOption.IGNORE_CASE)
    private val identity = "(-?[1-9][0-9]{0,18})_([1-9][0-9]{0,18})"
    private val trackId = Regex(identity)
    private val audio = Regex("audio$identity(?:[_/]([A-Za-z0-9_-]{1,128}))?")
    private val collection = Regex("(?:audio_playlist|music/album/)$identity(?:[_/]([A-Za-z0-9_-]{1,128}))?")
    private val key = Regex("[A-Za-z0-9_-]{1,128}")

    fun accessKey(value: String?) = value?.takeIf(key::matches)
    fun track(id: String, accessKey: String? = null): VkMusicLink.Track? {
        val match = trackId.matchEntire(id) ?: return null
        val owner = match.groupValues[1].toLongOrNull() ?: return null
        val number = match.groupValues[2].toLongOrNull() ?: return null
        return VkMusicLink.Track(owner, number, accessKey(accessKey))
    }

    fun parse(raw: String): VkMusicLink? {
        if (raw.length > 4096 || raw.any { it <= ' ' || it == '\\' }) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host?.lowercase() !in hosts ||
            uri.rawUserInfo != null || uri.port != -1) return null
        val parameters = uri.rawQuery.orEmpty().split('&').groupBy { it.substringBefore('=') }
        if (parameters["z"].orEmpty().size > 1 || parameters["access_key"].orEmpty().size > 1) return null
        val queryKey = parameters["access_key"]?.single()?.substringAfter('=', "")
        if (queryKey != null && accessKey(queryKey) == null) return null
        val target = if (parameters.containsKey("z")) {
            runCatching {
                URLDecoder.decode(parameters.getValue("z").single().substringAfter('=', ""), "UTF-8")
            }.getOrNull() ?: return null
        } else uri.rawPath.removePrefix("/").removeSuffix("/")
        val foundCollection = collection.matchEntire(target)
        val match = foundCollection ?: audio.matchEntire(target) ?: return null
        val owner = match.groupValues[1].toLongOrNull() ?: return null
        val id = match.groupValues[2].toLongOrNull() ?: return null
        val embeddedKey = match.groupValues[3].ifEmpty { null }
        if (embeddedKey != null && queryKey != null && embeddedKey != queryKey) return null
        val linkKey = embeddedKey ?: queryKey
        return if (foundCollection != null) VkMusicLink.Collection(owner, id, linkKey)
        else VkMusicLink.Track(owner, id, linkKey)
    }

    fun fromText(text: CharSequence): VkMusicLink? {
        if (text.length > 16_384) return null
        return url.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', '!', ';', ')', ']', '}')) }
    }

    fun collectionUrl(list: NativePlaylist): String? =
        if (list.owner == 0L || list.id <= 0) null
        else VkMusicLink.Collection(list.owner, list.id, accessKey(list.accessKey)).url
}

internal sealed class VkMusicLink(val owner: Long, val id: Long, val accessKey: String?) {
    class Track(owner: Long, id: Long, key: String?) : VkMusicLink(owner, id, key) {
        val trackId get() = "${owner}_$id"
        override val url get() = "https://vk.ru/audio" + audioReference
        val audioReference get() = trackId + (accessKey?.let { "_$it" } ?: "")
    }

    class Collection(owner: Long, id: Long, key: String?) : VkMusicLink(owner, id, key) {
        override val url get() = "https://vk.ru/music?z=audio_playlist${owner}_$id" +
            (accessKey?.let { "&access_key=$it" } ?: "")
    }

    abstract val url: String
    override fun toString() = "VkMusicLink([redacted])"
}
