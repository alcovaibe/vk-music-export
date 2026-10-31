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

/** Both reports are regenerated from their journal. Never substitute the new run for the previous failure. */
@Composable
internal fun LatestReportMenu(extraItems: @Composable androidx.compose.foundation.layout.ColumnScope.(()->Unit)->Unit={}) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var expanded by remember{mutableStateOf(false)}
    var legalPage by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf<String?>(null)}
    var previousAvailable by remember{mutableStateOf(false)}
    LaunchedEffect(expanded){if(expanded)previousAvailable=withContext(Dispatchers.IO){runCatching{Diagnostics.hasPreviousRun()}.getOrDefault(false)}}
    Box {
        IconButton(enabled=!busy,onClick={Diagnostics.button("report_menu");expanded=true},modifier=Modifier.testTag("home_menu")) {
            Icon(Icons.Default.MoreVert,if(busy)"Готовим JSON-отчёт" else "Меню")
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            extraItems{expanded=false}
            DropdownMenuItem(text={Text("Данные и доступ")},modifier=Modifier.testTag("open_privacy"),onClick={expanded=false;legalPage="privacy"})
            DropdownMenuItem(text={Text("Лицензия")},modifier=Modifier.testTag("open_licenses"),onClick={expanded=false;legalPage="licenses"})
            HorizontalDivider()
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
            if(previousAvailable)DropdownMenuItem(text={Text("Поделиться предыдущим отчётом")},
                modifier=Modifier.testTag("share_previous_report"),onClick={
                    expanded=false
                    if(!busy){busy=true;scope.launch {
                        try {
                            val intent=withContext(Dispatchers.IO){LatestReportShare.intent(context,previous=true)}
                            context.startActivity(Intent.createChooser(intent,"Отчёт предыдущего запуска"))
                        }catch(e: CancellationException){throw e}
                         catch(_: Exception){Toast.makeText(context,"Предыдущий отчёт недоступен. Готовые файлы сохранены.",Toast.LENGTH_LONG).show()}
                        finally{busy=false}
                    }}
                })
        }
    }
    legalPage?.let{app.alcovaibe.vkmusicexport.LegalInformationSheet(it){legalPage=null}}
}

internal object LatestReportShare {
    /** Call on IO. Defaults to only the latest/current run, never a previously exported file. */
    fun intent(context: Context,previous: Boolean=false): Intent {
        val file=Diagnostics.shareFile(Diagnostics.export(previous=previous),previous)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
        return Intent(Intent.ACTION_SEND).apply {
            type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"JSON-отчёт",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
