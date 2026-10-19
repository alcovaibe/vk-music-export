package app.alcovaibe.vkmusicexport.vk

import android.content.Context
import android.net.Uri

object CollectorBookmark {
    fun code(context: Context): String = "javascript:"+Uri.encode(context.assets.open("vk-chrome-bookmarklet.js").bufferedReader().use{it.readText()})
}
