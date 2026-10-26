package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class ArtworkState(val visible: Boolean=false,val running: Boolean=false,val total: Int=0,val processed: Int=0,
    val added: Int=0,val existing: Int=0,val missing: Int=0,val failed: Int=0,val current: String?=null,val message: String?=null,
    val issues: List<ArtworkIssue> = emptyList(),val skipped: List<ArtworkSkip> = emptyList(),val readySkipped: Int=0) {
    val noCover get()=skipped.count{it.reason==ArtworkSkipReason.NO_COVER}
    val unavailable get()=skipped.count{it.reason==ArtworkSkipReason.TRACK_UNAVAILABLE}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtworkSheet(state: ArtworkState,onStart: ()->Unit,onStop: ()->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("artwork_sheet")) {
            Text("Обложки сохранённых треков",style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp).padding(top=12.dp).testTag("artwork_results"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {Text("Добавим картинку внутрь сохранённых файлов. Имена, папки и качество звука сохранятся. Музыка повторно не скачивается.")}
                if(state.running)item {
                    if(state.total>0)LinearProgressIndicator(progress={state.processed.toFloat()/state.total},modifier=Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.current?.let{Text(it)}
                }
                if(state.running||state.message!=null)item {
                    Text("Обработано: ${state.processed}/${state.total}",Modifier.testTag("artwork_count"))
                    Text("Готовых файлов пропущено: ${state.readySkipped}")
                    Text("Добавлено: ${state.added} · уже есть: ${state.existing}\nВК не передал обложку: ${state.noCover}\nТрек недоступен: ${state.unavailable} · ошибки: ${state.failed}")
                }else item {Text("Готовые обложки пропускаются. Изменённые или недоступные файлы не перезаписываются. На время обработки приостанови скачивание и оставь приложение открытым.")}
                state.message?.let{item {Text(it,Modifier.testTag("artwork_result"))}}
                for(reason in ArtworkSkipReason.entries) {
                    val rows=state.skipped.filter{it.reason==reason}
                    if(rows.isNotEmpty()) {
                        item {Text(if(reason==ArtworkSkipReason.NO_COVER)"ВК не передал обложку (${rows.size})" else "Треки недоступны (${rows.size})",style=MaterialTheme.typography.titleSmall)}
                        items(rows,key={"skip_${it.item}"}){row->
                            Column(Modifier.testTag("artwork_skip_${row.item}")) {
                                Text(row.title,style=MaterialTheme.typography.titleSmall)
                                Text(row.artist,style=MaterialTheme.typography.bodyMedium)
                                Text(row.reason.label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if(state.issues.isNotEmpty()) {
                    item {Text("Ошибки обработки (${state.issues.size})",style=MaterialTheme.typography.titleSmall)}
                    items(state.issues,key={"issue_${it.item}"}){issue->
                        Column(Modifier.testTag("artwork_issue_${issue.item}")) {
                            Text(issue.title.ifEmpty{issue.name},style=MaterialTheme.typography.titleSmall)
                            if(issue.artist.isNotEmpty())Text(issue.artist,style=MaterialTheme.typography.bodyMedium)
                            Text(issue.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                            Text("${issue.code.name} · ${issue.step.name}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.running)"Свернуть" else "Закрыть")}
                if(state.running)OutlinedButton(onClick=onStop,modifier=Modifier.testTag("artwork_stop")){Text("Остановить")}
                else Button(onClick=onStart,modifier=Modifier.testTag("artwork_start")){Text(if(state.message==null)"Добавить обложки" else "Повторить неудачные")}
            }
        }
    }
}
