package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicatesSheet(state: DuplicatesState,onScan: ()->Unit,onRequest: (MusicDuplicate)->Unit,onConfirm: ()->Unit,
    onCancelConfirm: ()->Unit,onSkip: (MusicDuplicate)->Unit,onStop: ()->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("duplicates_sheet")) {
            Text(if(state.consent==null)"Дубликаты в «Моей музыке» ВК" else "Удалить этот дубликат из ВК?",style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=420.dp).padding(top=12.dp).testTag("duplicates_list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(state.busy)item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.progress.stage,Modifier.testTag("duplicates_progress"))
                    if(state.progress.total>0)Text("${state.progress.done}/${state.progress.total}")
                    state.progress.track?.let{Text("${it.title}\n${it.artist}")}
                    if(state.progress.bytes>0)Text("Получено: ${state.progress.bytes/1024} КиБ")
                }
                state.message?.let{item{Text(it,Modifier.testTag("duplicates_result"))}}
                val consent=state.consent
                if(consent!=null) {
                    item {
                        val row=consent.duplicate
                        Text("Удаляем одну запись:",style=MaterialTheme.typography.titleSmall)
                        Text("${row.remove.title}\n${row.remove.artist}\nID: ${row.remove.id}")
                        HorizontalDivider(Modifier.padding(vertical=12.dp))
                        Text("Оставляем в профиле:",style=MaterialTheme.typography.titleSmall)
                        Text("${row.keep.title}\n${row.keep.artist}\nID: ${row.keep.id}")
                        Text("Название, исполнитель и SHA-256 аудиоданных совпали. Перед запросом проверим обе записи заново. Локальные файлы останутся на телефоне.")
                    }
                }else {
                    item {
                        Text("Сначала ищем совпадения названия и исполнителя, затем временно скачиваем эти записи и сравниваем SHA-256 аудиоданных без тегов и обложек. Проверка использует интернет и свободное место. В папке Music ничего не меняется.",style=MaterialTheme.typography.bodySmall)
                        Text("Одна проверенная запись в каждой группе остаётся. Каждое удаление подтверждается отдельно; общего удаления всех записей нет.",style=MaterialTheme.typography.bodySmall)
                    }
                    state.report?.let{report->
                        item {
                            Text("Дубликатов: ${state.found} · удалено: ${state.removed} · пропущено: ${state.skipped} · ожидает: ${state.remaining}",Modifier.testTag("duplicates_counts"))
                            Text("В библиотеке: ${report.tracks} · проверено SHA-256: ${report.checked}",style=MaterialTheme.typography.bodySmall)
                        }
                        items(report.duplicates,key={it.remove.id}){row->
                            Column(Modifier.testTag("duplicate_${row.remove.id}")) {
                                Text(row.remove.title,style=MaterialTheme.typography.titleSmall);Text(row.remove.artist)
                                Text("Запись ${row.remove.id}: ${row.status.label}",style=MaterialTheme.typography.bodySmall)
                                Text("Останется запись ${row.keep.id}",style=MaterialTheme.typography.bodySmall)
                                if(state.phase==DuplicatePhase.READY&&row.status==DuplicateStatus.PENDING) {
                                    OutlinedButton(onClick={onRequest(row)},border=BorderStroke(1.dp,MaterialTheme.colorScheme.error),
                                        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                                        modifier=Modifier.testTag("request_duplicate_${row.remove.id}")){Text("Удалить этот дубликат")}
                                    TextButton(onClick={onSkip(row)}){Text("Пропустить")}
                                }
                            }
                        }
                        if(report.problems.isNotEmpty())item{Text("Не подтверждены для удаления (${report.problems.size})",style=MaterialTheme.typography.titleSmall)}
                        items(report.problems.withIndex().toList(),key={"problem_${it.index}"}){(_,problem)->
                            Column {Text("${problem.track.title}\n${problem.track.artist}");Text(problem.reason,style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                }
            }
            if(state.consent!=null) {
                OutlinedButton(onClick=onConfirm,modifier=Modifier.fillMaxWidth().padding(top=12.dp).testTag("confirm_duplicate"),
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.error),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)) {
                    Text("Удалить эту запись из ВК")
                }
                TextButton(onClick=onCancelConfirm){Text("Отмена")}
            }else Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.busy)"Свернуть" else "Закрыть")}
                if(state.busy)OutlinedButton(onClick=onStop){Text("Остановить")}
                else Button(onClick=onScan,modifier=Modifier.testTag("scan_duplicates")){Text(if(state.report==null)"Начать поиск" else "Новый поиск")}
            }
        }
    }
}
