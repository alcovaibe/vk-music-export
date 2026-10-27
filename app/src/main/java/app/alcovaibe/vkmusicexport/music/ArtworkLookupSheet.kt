package app.alcovaibe.vkmusicexport.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private fun audioTime(seconds: Int)="${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtworkLookupSheet(state: ArtworkLookupState,onQuery:(String)->Unit,onSearch:(Int)->Unit,onPreview:(ArtworkMatch)->Unit,
    onConfirm:()->Unit,onBack:()->Unit,onStop:()->Unit,onDismiss:()->Unit) {
    val target=state.target
    val choice=state.choice
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("artwork_lookup_sheet")) {
            Text(if(state.phase==LookupPhase.CONFIRM)"Добавить эту обложку?" else "Найти обложку в ВК",style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=460.dp).padding(top=12.dp).testTag("artwork_lookup_list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                target?.let{item {
                    Text("Сохранённый файл",style=MaterialTheme.typography.labelLarge)
                    Text("${it.track.title}\n${it.track.artist} · ${audioTime(it.track.duration)}")
                }}
                if(state.busy)item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(when(state.phase){LookupPhase.OPENING->"Проверяем сохранённый файл…";LookupPhase.SEARCHING->"Ищем записи в ВК…";
                        LookupPhase.PREVIEWING->"Загружаем обложку…";else->"Проверяем файл и добавляем обложку…"},Modifier.testTag("artwork_lookup_progress"))
                }
                state.message?.let{item{Text(it,Modifier.testTag("artwork_lookup_message"))}}
                if(choice!=null&&state.phase in setOf(LookupPhase.CONFIRM,LookupPhase.APPLYING))item {
                    val bitmap=remember(choice.id){BitmapFactory.decodeByteArray(choice.image.bytes,0,choice.image.bytes.size)?.asImageBitmap()}
                    bitmap?.let{Image(it,contentDescription="Обложка выбранной записи",modifier=Modifier.fillMaxWidth().height(180.dp).testTag("artwork_lookup_image"),contentScale=ContentScale.Fit)}
                    HorizontalDivider(Modifier.padding(vertical=12.dp))
                    Text("Обложка из найденной записи",style=MaterialTheme.typography.labelLarge)
                    Text(choice.match.track.title,style=MaterialTheme.typography.titleMedium)
                    Text("${choice.match.track.artist} · ${audioTime(choice.match.track.duration)}")
                    choice.match.track.album?.let{Text("Альбом: ${it.title}")}
                    val delta=abs(choice.match.track.duration-choice.target.track.duration)
                    if(delta>0)Text("Длительность отличается на $delta с. Проверь версию песни.",color=MaterialTheme.colorScheme.error)
                    Text("Подтверди, что выбрал нужную обложку. Совпадение аудиозаписи не проверялось: скачивается только картинка. Имя, папка и аудиоданные файла сохранятся. Запись в профиле ВК не добавляется.")
                }else if(state.phase!=LookupPhase.DONE) {
                    item {
                        Text("Найди другую запись той же песни или вставь ссылку на отдельный трек. Оставь музыкальный экран открытым до завершения.",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(state.query,onQuery,enabled=!state.busy,label={Text("Исполнитель и название или ссылка ВК")},
                            modifier=Modifier.fillMaxWidth().testTag("artwork_lookup_query"),minLines=2,maxLines=4)
                        Button(onClick={onSearch(0)},enabled=!state.busy&&target!=null&&state.query.isNotBlank(),
                            modifier=Modifier.padding(top=8.dp).testTag("artwork_lookup_search")){Text("Найти")}
                        Text("Ссылки: https://vk.com/audio123_456 или https://vk.com/music?z=audio123_456. Короткую ссылку сначала открой в ВК и скопируй полную.",style=MaterialTheme.typography.bodySmall)
                    }
                    state.results?.let{page->
                        item {
                            Text(if(page.byLink)"Запись по ссылке" else "Варианты: ${page.offset+if(page.matches.isEmpty())0 else 1}–${page.offset+page.matches.size} из ${page.count}",style=MaterialTheme.typography.titleSmall)
                            Text("Выбор только вручную: одинаковое название не гарантирует одинаковую версию песни.",style=MaterialTheme.typography.bodySmall)
                        }
                        items(page.matches,key={it.track.id}){match->
                            Column(Modifier.fillMaxWidth().testTag("artwork_match_${match.track.id}")) {
                                Text(match.track.title,style=MaterialTheme.typography.titleMedium)
                                Text("${match.track.artist} · ${audioTime(match.track.duration)}")
                                match.track.album?.let{Text(it.title,style=MaterialTheme.typography.bodySmall)}
                                if(!match.selectable)Text(when{match.track.restricted||match.track.duration<=0->"Запись недоступна.";
                                    match.coverError!=null->"Данные обложки не прошли проверку (${match.coverError.name}).";else->"ВК не передал обложку."},style=MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick={onPreview(match)},enabled=!state.busy&&match.selectable,
                                    modifier=Modifier.testTag("preview_artwork_${match.track.id}")){Text("Посмотреть обложку")}
                            }
                        }
                        if(!page.byLink)item {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick={onSearch(page.offset-ArtworkQuery.PAGE_SIZE)},enabled=!state.busy&&page.offset>0){Text("Предыдущие")}
                                TextButton(onClick={onSearch(page.offset+ArtworkQuery.PAGE_SIZE)},
                                    enabled=!state.busy&&page.matches.isNotEmpty()&&page.offset+ArtworkQuery.PAGE_SIZE<minOf(page.count,ArtworkQuery.MAX_RESULTS),
                                    modifier=Modifier.testTag("artwork_lookup_more")){Text("Ещё варианты")}
                            }
                            if(page.count>ArtworkQuery.MAX_RESULTS)Text("Доступны первые ${ArtworkQuery.MAX_RESULTS} вариантов. Уточни запрос для другого результата.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if(state.phase==LookupPhase.CONFIRM) {
                Button(onClick=onConfirm,modifier=Modifier.fillMaxWidth().padding(top=12.dp).testTag("confirm_artwork_choice")){Text("Добавить эту обложку к файлу")}
                TextButton(onClick=onBack,modifier=Modifier.testTag("cancel_artwork_choice")){Text("Назад к вариантам")}
            }else Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onDismiss){Text(if(state.busy)"Остановить и закрыть" else "Закрыть")}
                if(state.busy)OutlinedButton(onClick=onStop){Text("Остановить")}
            }
        }
    }
}
