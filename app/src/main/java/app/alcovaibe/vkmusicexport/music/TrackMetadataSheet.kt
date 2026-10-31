package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

@Serializable internal data class TrackMetadataIssue(val name: String,val reason: String,val target: TrackMetadataTarget?=null,val selectedId: String?=null,val resolved: Boolean=false)
internal data class TrackMetadataState(val visible: Boolean=false,val running: Boolean=false,val total: Int=0,val processed: Int=0,
    val updated: Int=0,val current: Int=0,val unavailable: Int=0,val skipped: Int=0,val failed: Int=0,
    val name: String?=null,val message: String?=null,val issues: List<TrackMetadataIssue> = emptyList(),val resumable: Boolean=false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackMetadataSheet(state: TrackMetadataState,onStart:()->Unit,onStop:()->Unit,onDismiss:()->Unit,onChoose:(TrackMetadataTarget)->Unit={}) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("track_metadata_sheet")) {
            Text("Названия и пометки",style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp).padding(top=12.dp).testTag("track_metadata_results"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {Text("Получим названия и версии песен по сохранённым ID ВК, даже если ты убрал их из своей музыки. Обновим имена файлов, музыкальные теги и M3U8. Звук повторно не скачивается.")}
                item {Text("Если ВК больше не отдаёт запись, выбери другую запись вручную: поиском или по полной ссылке на песню. Изменённые файлы и неподтверждённые локальные замены пропускаются.",style=MaterialTheme.typography.bodySmall)}
                item {Text("Токен обновляется автоматически, пока ВК разрешает продление. После повторного входа можно продолжить сохранённую проверку. Готовые позиции повторно не запрашиваются.",style=MaterialTheme.typography.bodySmall)}
                if(state.running)item {
                    if(state.total>0)LinearProgressIndicator(progress={state.processed.toFloat()/state.total},modifier=Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.name?.let{Text(it)}
                }
                if(state.running||state.message!=null)item {
                    Text("Обработано: ${state.processed}/${state.total}",Modifier.testTag("track_metadata_count"))
                    Text("Исправлено: ${state.updated} · уже актуально: ${state.current}\nНедоступны в ВК: ${state.unavailable}\nПропущено: ${state.skipped} · ошибки: ${state.failed}")
                }
                state.message?.let{item{Text(it,Modifier.testTag("track_metadata_result"))}}
                if(state.unavailable>0&&state.issues.none{it.target!=null})item{Text("Для старого отчёта заверши проверку и нажми «Проверить снова», чтобы появился ручной выбор записи.",style=MaterialTheme.typography.bodySmall)}
                itemsIndexed(state.issues){index,issue->
                    Column(Modifier.testTag("track_metadata_issue_$index")) {
                        Text(issue.name,style=MaterialTheme.typography.titleSmall)
                        Text(issue.reason,style=MaterialTheme.typography.bodySmall)
                        issue.target?.let{target->OutlinedButton(onClick={onChoose(target)},enabled=!state.running,
                            modifier=Modifier.testTag("metadata_lookup_$index")){Text(if(issue.resolved)"Выбрать другую запись" else "Найти запись вручную")}}
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.running)"Свернуть" else "Закрыть")}
                if(state.running)OutlinedButton(onClick=onStop,modifier=Modifier.testTag("track_metadata_stop")){Text("Остановить")}
                else Button(onClick=onStart,modifier=Modifier.testTag("track_metadata_start")){Text(if(state.resumable)"Продолжить" else if(state.message==null)"Обновить" else "Проверить снова")}
            }
            if(!state.running&&state.message==null)Text("Приостанови скачивание и оставь музыкальный экран открытым до завершения.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
