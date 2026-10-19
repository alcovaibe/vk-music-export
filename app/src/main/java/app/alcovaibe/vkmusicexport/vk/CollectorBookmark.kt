package app.alcovaibe.vkmusicexport.vk

import android.content.Context

object CollectorBookmark {
    fun code(context: Context): String {
        val script = context.assets.open("vk-chrome-bookmarklet.js").bufferedReader().use { it.readText() }
        check(script.all { it.code in 32..126 } && script.endsWith(";void\"VKM_END_051\";"))
        // JavaScript URLs are decoded once by Chromium. Escape literal percent/hash,
        // keep the single-line ASCII script readable and avoid encoded line breaks.
        return "javascript:" + script.replace("%", "%25").replace("#", "%23")
    }
}
