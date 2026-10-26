package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class ArtworkState(val visible: Boolean=false,val running: Boolean=false,val total: Int=0,val processed: Int=0,
    val added: Int=0,val existing: Int=0,val missing: Int=0,val failed: Int=0,val current: String?=null,val message: String?=null,val issues: List<ArtworkIssue> = emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtworkSheet(state: ArtworkState,onStart: ()->Unit,onStop: ()->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("artwork_sheet")) {
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Обложки сохранённых треков",style=MaterialTheme.typography.titleLarge)
                Text("Добавим картинку внутрь файлов, которые приложение сохранило и помнит. Имена, папки и качество звука сохранятся. Музыка повторно не скачивается.")
                if(state.running) {
                    if(state.total>0)LinearProgressIndicator(progress={state.processed.toFloat()/state.total},modifier=Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.current?.let{Text(it)}
                }
                if(state.running||state.message!=null) {
                    Text("Обработано: ${state.processed}/${state.total}",Modifier.testTag("artwork_count"))
                    Text("Добавлено: ${state.added} · уже есть: ${state.existing}\nНет обложки или трек недоступен: ${state.missing} · ошибки: ${state.failed}")
                }else Text("Готовые обложки пропускаются. Изменённые или недоступные файлы не перезаписываются. На время обработки приостанови скачивание и оставь приложение открытым.")
                state.message?.let{Text(it,Modifier.testTag("artwork_result"))}
                if(state.issues.isNotEmpty()) {
                    Text("Последние проблемы",style=MaterialTheme.typography.titleSmall)
                    state.issues.forEach{issue->
                        Text("${issue.item}. ${issue.name}",style=MaterialTheme.typography.bodySmall)
                        Text("${issue.reason} Код: ${issue.code.name} · ${issue.step.name}",
                            Modifier.testTag("artwork_issue_${issue.item}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.running)"Свернуть" else "Закрыть")}
                if(state.running)OutlinedButton(onClick=onStop,modifier=Modifier.testTag("artwork_stop")){Text("Остановить")}
                else Button(onClick=onStart,modifier=Modifier.testTag("artwork_start")){Text(if(state.message==null)"Добавить обложки" else "Повторить обработку")}
            }
        }
    }
}
