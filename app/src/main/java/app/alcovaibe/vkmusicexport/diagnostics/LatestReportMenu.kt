package app.alcovaibe.vkmusicexport.diagnostics

import android.content.*
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.FileProvider
import kotlinx.coroutines.*

/** One menu action, always a fresh report of the current run. No clipboard or older-run selector. */
@Composable
internal fun LatestReportMenu() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var expanded by remember{mutableStateOf(false)}
    Box {
        IconButton(enabled=!busy,onClick={Diagnostics.button("report_menu");expanded=true},modifier=Modifier.testTag("home_menu")) {
            Icon(Icons.Default.MoreVert,if(busy)"Готовим JSON-отчёт" else "Меню")
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            DropdownMenuItem(text={Text("Поделиться JSON-отчётом")},modifier=Modifier.testTag("share_latest_report"),onClick={
                expanded=false
                if(!busy) {
                    busy=true;Diagnostics.button("share_log")
                    scope.launch {
                        try {
                            val intent=withContext(Dispatchers.IO){LatestReportShare.intent(context)}
                            context.startActivity(Intent.createChooser(intent,"Поделиться JSON-отчётом"))
                        }catch(e: CancellationException){throw e}
                         catch(_: Exception){
                            Toast.makeText(context,"Не удалось подготовить JSON-отчёт. Повтори попытку.",Toast.LENGTH_LONG).show()
                            Diagnostics.event("diagnostic_export_failed",mapOf("previous" to false,"share" to true))
                        }finally{busy=false}
                    }
                }
            })
        }
    }
}

internal object LatestReportShare {
    /** Call on IO. Defaults to only the latest/current run, never a previously exported file. */
    fun intent(context: Context): Intent {
        val file=Diagnostics.shareFile(Diagnostics.export(previous=false))
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
        return Intent(Intent.ACTION_SEND).apply {
            type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"JSON-отчёт",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
