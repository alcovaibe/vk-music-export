package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private fun metadataTime(seconds: Int)="${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackMetadataLookupSheet(state: MetadataLookupState,onQuery:(String)->Unit,onSearch:(Int)->Unit,onPreview:(NativeTrack)->Unit,
    onConfirm:()->Unit,onBack:()->Unit,onStop:()->Unit,onDismiss:()->Unit) {
    val choice=state.choice
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("metadata_lookup_sheet")) {
            Text(if(state.phase==LookupPhase.CONFIRM)"Обновить название и версию?" else "Найти запись в ВК",style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=460.dp).padding(top=12.dp).testTag("metadata_lookup_list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                state.target?.let{item {
                    Text("Сейчас в файле",style=MaterialTheme.typography.labelLarge)
                    Text(it.track.displayTitle,Modifier.testTag("metadata_before"),style=MaterialTheme.typography.titleMedium)
                    Text("${it.track.artist} · ${metadataTime(it.track.duration)}")
                }}
                if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text(when(state.phase){
                    LookupPhase.OPENING->"Проверяем сохранённый файл…";LookupPhase.SEARCHING->"Ищем записи в ВК…";
                    LookupPhase.PREVIEWING->"Проверяем выбранную запись…";else->"Обновляем название и пометку версии…"})}
                state.message?.let{item{Text(it,Modifier.testTag("metadata_lookup_message"))}}
                if(choice!=null&&state.phase in setOf(LookupPhase.CONFIRM,LookupPhase.APPLYING))item {
                    HorizontalDivider(Modifier.padding(vertical=8.dp))
                    Text("Новое название и версия",style=MaterialTheme.typography.labelLarge)
                    Text(choice.selected.displayTitle,Modifier.testTag("metadata_after"),style=MaterialTheme.typography.titleMedium)
                    Text("Запись ВК: ${choice.selected.artist} · ${metadataTime(choice.selected.duration)}")
                    val delta=abs(choice.selected.duration-choice.target.track.duration)
                    if(delta>0)Text("Длительность отличается на $delta с. Проверь, что это нужная версия песни.",color=MaterialTheme.colorScheme.error)
                    Text("Подтверди соответствие вручную. Обновятся название, пометка версии, имя файла и M3U8. Звук, обложка, исполнитель, альбом и исходный ID сохранятся. Выбранную запись будем использовать при следующих проверках.")
                }else if(state.phase!=LookupPhase.DONE) {
                    item {
                        Text("Найди ту же версию песни или вставь полную ссылку на отдельный трек. Название само по себе не подтверждает совпадение.",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(state.query,onQuery,enabled=!state.busy,label={Text("Исполнитель и название или ссылка ВК")},
                            modifier=Modifier.fillMaxWidth().testTag("metadata_lookup_query"),minLines=2,maxLines=4)
                        Button(onClick={onSearch(0)},enabled=!state.busy&&state.target!=null&&state.query.isNotBlank(),
                            modifier=Modifier.padding(top=8.dp).testTag("metadata_lookup_search")){Text("Найти")}
                        Text("Например: https://vk.com/audio123_456 или https://vk.com/music?z=audio123_456",style=MaterialTheme.typography.bodySmall)
                    }
                    state.results?.let{page->
                        item{Text(if(page.byLink)"Запись по ссылке" else "Варианты: ${page.offset+if(page.tracks.isEmpty())0 else 1}–${page.offset+page.tracks.size} из ${page.count}")}
                        items(page.tracks,key={it.id}){track->Column(Modifier.fillMaxWidth()) {
                            Text(track.displayTitle,style=MaterialTheme.typography.titleMedium)
                            Text("${track.artist} · ${metadataTime(track.duration)}")
                            OutlinedButton(onClick={onPreview(track)},enabled=!state.busy,
                                modifier=Modifier.testTag("preview_metadata_${track.id}")){Text("Проверить название и версию")}
                        }}
                        if(!page.byLink)item {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick={onSearch(page.offset-ArtworkQuery.PAGE_SIZE)},enabled=!state.busy&&page.offset>0){Text("Предыдущие")}
                                TextButton(onClick={onSearch(page.offset+ArtworkQuery.PAGE_SIZE)},enabled=!state.busy&&page.tracks.isNotEmpty()&&
                                    page.offset+ArtworkQuery.PAGE_SIZE<minOf(page.count,ArtworkQuery.MAX_RESULTS)){Text("Ещё варианты")}
                            }
                            if(page.count>ArtworkQuery.MAX_RESULTS)Text("Уточни запрос: доступны первые ${ArtworkQuery.MAX_RESULTS} вариантов.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if(state.phase==LookupPhase.CONFIRM) {
                Button(onClick=onConfirm,modifier=Modifier.fillMaxWidth().padding(top=12.dp).testTag("confirm_metadata_choice")){Text("Применить название и версию")}
                TextButton(onClick=onBack){Text("Назад к вариантам")}
            }else Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.busy)"Остановить и закрыть" else "Закрыть")}
                if(state.busy)OutlinedButton(onClick=onStop){Text("Остановить")}
            }
        }
    }
}
