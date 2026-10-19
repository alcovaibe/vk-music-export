package app.alcovaibe.vkmusicexport.vk

import android.content.Context

object CollectorBookmark {
    const val MAX_URL_CHARS = 4950
    fun code(context: Context): String {
        return load(context,"vk-chrome-bookmarklet.url","/*VKM_END_052*/")
    }
    fun layoutCode(context: Context): String = load(context,"vk-layout-bookmarklet.url","/*VKM_LAYOUT_1*/")
    private fun load(context: Context,asset: String,marker: String): String {
        val url = context.assets.open(asset).bufferedReader().use { it.readText() }
        check(url.length<=MAX_URL_CHARS && url.all { it.code in 32..126 } &&
            url.startsWith("javascript:") && url.endsWith(marker))
        return url
    }
}
