package app.alcovaibe.vkmusicexport.music

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Explicit user export of song metadata. Kept separate from redacted diagnostic reports. */
internal object ExportReportShare {
    fun intent(context: Context): Intent {
        val json=Json{encodeDefaults=true}
        val text=AudioExportStore(context).use{store->
            // One SQLite snapshot, including every failure and source, not only the last ten.
            val db=store.readableDatabase;db.beginTransaction()
            try {
                val snapshot=store.snapshot()
                val failures=snapshot.failures.associateBy{it.task.key}
                buildJsonObject {
                    put("schema",1);put("account",snapshot.account);put("state",snapshot.status)
                    put("selected",snapshot.total);put("saved",snapshot.done)
                    put("failed",snapshot.failed);put("sourceIssues",snapshot.sourceIssues)
                    put("sources",json.encodeToJsonElement(snapshot.sourceFailures))
                    putJsonArray("tracks") {
                        for((task,state)in store.taskRows())add(buildJsonObject {
                            put("track",json.encodeToJsonElement(task.track));put("source",json.encodeToJsonElement(task.source))
                            put("position",task.position);put("occurrence",task.occurrence);put("state",state)
                            failures[task.key]?.let{failure->
                                put("error",failure.error.name)
                                failure.details?.let{put("errorDetails",json.encodeToJsonElement(it))}
                            }
                            store.existing(task.key)?.let{file->
                                put("file",file.relativePath+file.displayName);put("sha256",file.sha256)
                                put("catalogueVerified",file.catalogueVerified)
                            }
                        })
                    }
                }.toString()
            } finally {db.endTransaction()}
        }
        val directory=File(context.cacheDir,"diagnostic-export").apply{mkdirs()}
        val file=File(directory,"vk-music-export-result.json").apply{writeText(text)}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
        return Intent(Intent.ACTION_SEND).apply {
            type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"Отчёт о музыке",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
