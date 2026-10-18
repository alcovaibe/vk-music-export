package app.alcovaibe.vkmusicexport.diagnostics

import android.content.*
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.*

@Composable
fun DiagnosticControls(compact: Boolean = false, onMarkWhite: (() -> Unit)? = null, onProfile: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var offerFile by remember { mutableStateOf<Boolean?>(null) }
    fun action(previous: Boolean, share: Boolean) {
        if (busy) return
        Diagnostics.button(if(share) "share_log" else if(previous) "copy_previous_log" else "copy_log")
        busy=true
        scope.launch {
            try {
                val json=withContext(Dispatchers.IO) { Diagnostics.export(previous) }
                // Binder's 1 MB budget is shared. Use a conservative UTF-16 threshold.
                if (!share && !fitsClipboard(json)) {
                    offerFile=previous
                } else if (share) {
                    val file=withContext(Dispatchers.IO) { Diagnostics.shareFile(json) }
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
                    val send=Intent(Intent.ACTION_SEND).apply {
                        type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
                        clipData=ClipData.newUri(context.contentResolver,"Диагностический JSON",uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send,"Поделиться JSON-файлом"))
                } else {
                    try {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                            ClipData.newPlainText("VK Music Export — диагностический JSON",json).apply {
                                description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                            })
                        Toast.makeText(context,if(previous) "Лог предыдущего запуска скопирован" else "Лог скопирован",Toast.LENGTH_SHORT).show()
                        Diagnostics.event("clipboard_success",mapOf("previous" to previous,"characters" to json.length))
                    } catch (_: RuntimeException) { offerFile=previous }
                }
            } catch (_: Exception) {
                Toast.makeText(context,if(previous) "Предыдущий лог недоступен. Попробуйте после следующего запуска." else "Не удалось получить лог. Повторите попытку или перезапустите приложение.",Toast.LENGTH_LONG).show()
                Diagnostics.event("diagnostic_export_failed",mapOf("previous" to previous,"share" to share))
            } finally { busy=false }
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        TextButton(enabled=!busy,onClick={action(false,false)}) { Text(if(busy) "Готовим лог…" else "Копировать лог") }
        Box {
            TextButton(enabled=!busy,onClick={Diagnostics.button("diagnostic_menu");menu=true}) { Text(if(compact) "Ещё логи" else "Другие логи") }
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                if(onMarkWhite != null) DropdownMenuItem(text={Text("Отметить белый экран")},onClick={
                    menu=false;onMarkWhite();Toast.makeText(context,"Белый экран отмечен. Теперь скопируйте лог или отправьте JSON.",Toast.LENGTH_LONG).show()
                })
                if(onProfile != null) DropdownMenuItem(text={Text("Профиль входа")},onClick={menu=false;Diagnostics.button("browser_profile_dialog");onProfile()})
                DropdownMenuItem(text={Text("Копировать предыдущий запуск")},onClick={menu=false;action(true,false)})
                DropdownMenuItem(text={Text("Поделиться JSON-файлом")},onClick={menu=false;action(false,true)})
                DropdownMenuItem(text={Text("Поделиться предыдущим JSON")},onClick={menu=false;action(true,true)})
            }
        }
    }
    offerFile?.let { previous ->
        AlertDialog(onDismissRequest={offerFile=null},title={Text("Лог слишком большой для буфера обмена")},
            text={Text("JSON не обрезан. Передайте полный лог файлом.")},
            confirmButton={TextButton(onClick={offerFile=null;action(previous,true)}) {Text("Поделиться JSON-файлом")}},
            dismissButton={TextButton(onClick={Diagnostics.button("cancel_log_share");offerFile=null}) {Text("Отмена")}})
    }
}

internal fun fitsClipboard(json: String): Boolean = json.length * 2L <= 240_000
