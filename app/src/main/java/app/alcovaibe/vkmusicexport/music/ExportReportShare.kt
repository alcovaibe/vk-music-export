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
        val directory=File(context.cacheDir,"diagnostic-export").apply{mkdirs()}
        val file=File(directory,"vk-music-export-result-${java.util.UUID.randomUUID()}.json")
        try {
            AudioExportStore(context).use{store->
                val db=store.readableDatabase;db.beginTransaction()
                try {
                    val snapshot=store.snapshot()
                    file.bufferedWriter(Charsets.UTF_8).use{out->
                        val header=buildJsonObject {
                            put("schema",1);put("account",snapshot.account);put("state",snapshot.status)
                            put("selected",snapshot.total);put("saved",snapshot.done);put("failed",snapshot.failed);put("sourceIssues",snapshot.sourceIssues)
                        }.toString()
                        out.write(header.dropLast(1));out.write(",\"sources\":[")
                        var first=true
                        store.forEachReportSource{source->if(!first)out.write(",");first=false;out.write(json.encodeToString(source))}
                        out.write("],\"tracks\":[");first=true
                        store.forEachReportRow{row->
                            if(!first)out.write(",");first=false
                            val task=row.task
                            out.write(buildJsonObject {
                                put("track",json.encodeToJsonElement(task.track));put("source",json.encodeToJsonElement(task.source))
                                put("position",task.position);put("occurrence",task.occurrence);put("state",row.state)
                                row.error?.let{put("error",it.name)}
                                row.details?.let{put("errorDetails",json.encodeToJsonElement(it))}
                                row.file?.let{saved->put("file",saved.relativePath+saved.displayName);put("sha256",saved.sha256);put("catalogueVerified",saved.catalogueVerified)}
                            }.toString())
                        }
                        out.write("]}")
                    }
                }finally{db.endTransaction()}
            }
        }catch(e: Throwable){file.delete();throw e}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
        return Intent(Intent.ACTION_SEND).apply {
            type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"Отчёт о музыке",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
