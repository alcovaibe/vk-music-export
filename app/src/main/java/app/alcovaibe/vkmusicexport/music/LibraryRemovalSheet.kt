package app.alcovaibe.vkmusicexport.music

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryRemoveButton(inLibrary: Boolean,enabled: Boolean,onClick: ()->Unit) {
    if(inLibrary)IconButton(enabled=enabled,onClick=onClick,modifier=Modifier.testTag("remove_from_vk")) {
        Icon(Icons.Default.Delete,"Удалить из моей музыки ВК",tint=MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryRemovalSheet(state: LibraryRemovalState,onConfirm: ()->Unit,onDismiss: ()->Unit) {
    if(!state.visible)return
    val context=LocalContext.current
    var openError by remember(state.track?.id){mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("library_removal_sheet")) {
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(state.phase==LibraryRemovalPhase.CONFIRM)"Удалить из «Моей музыки» ВК?" else "Удаление из ВК",style=MaterialTheme.typography.titleLarge)
                state.track?.let{Text("${it.artist} — ${it.title}")}
                when(state.phase) {
                    LibraryRemovalPhase.CHECKING->{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Проверяем скачанную копию…")}
                    LibraryRemovalPhase.CONFIRM->{
                        Text("Скачанный файл останется на устройстве. Перед удалением проверь его воспроизведение.")
                        state.file?.let{row->
                            Text(row.relativePath,style=MaterialTheme.typography.bodySmall)
                            Text(row.displayName,style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={
                                try {
                                    val uri=Uri.parse(row.uri)
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,row.mime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("Audio",uri)})
                                }catch(_: Exception){openError=true}
                            },modifier=Modifier.testTag("open_removal_copy")){Text("Открыть скачанный файл")}
                            if(openError)Text("Не удалось открыть файл. Проверь его через приложение для музыки.",color=MaterialTheme.colorScheme.error)
                        }
                    }
                    LibraryRemovalPhase.SENDING->{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Отправляем запрос в ВК…")}
                    else->state.message?.let{Text(it,Modifier.testTag("library_removal_result"))}
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.phase==LibraryRemovalPhase.CONFIRM)"Отмена" else "Закрыть")}
                if(state.phase==LibraryRemovalPhase.CONFIRM)OutlinedButton(onClick=onConfirm,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.error),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                    modifier=Modifier.testTag("confirm_remove_from_vk")){Text("Удалить из ВК")}
            }
        }
    }
}
