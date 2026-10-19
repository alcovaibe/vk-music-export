package app.alcovaibe.vkmusicexport.vk

import android.content.Context

object CollectorBookmark {
    const val MAX_URL_CHARS = 4950
    fun code(context: Context): String {
        val url = context.assets.open("vk-chrome-bookmarklet.url").bufferedReader().use { it.readText() }
        check(url.length<=MAX_URL_CHARS && url.all { it.code in 32..126 } &&
            url.startsWith("javascript:") && url.endsWith("/*VKM_END_052*/"))
        return url
    }
}
