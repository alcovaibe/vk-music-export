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
internal fun SavedFilesSheet(state: SavedFilesState,onCheck: ()->Unit,onSearch: ()->Unit,onSelect: (PublishedAudio)->Unit,
    onConfirm: ()->Unit,onForget: (PublishedAudio)->Unit,onConfirmForget: ()->Unit,onBack: ()->Unit,onStop: ()->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("saved_files_sheet")) {
            Text("Проверка сохранённых файлов",style=MaterialTheme.typography.titleLarge)
            if(state.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp))
                Text("Проверено: ${state.processed}/${state.total}")
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=380.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                state.message?.let{item{Text(it,Modifier.testTag("saved_files_result"))}}
                val preview=state.preview
                val forget=state.forget
                if(preview!=null) {
                    item {
                        Text("Восстановить копию выбранного файла?",style=MaterialTheme.typography.titleMedium)
                        Text("Запись: ${preview.before.track.artist} — ${preview.before.track.displayTitle}")
                        Text("Выбран: ${preview.name}")
                        Text(if(preview.exact)"Байты полностью совпали с сохранённой копией."
                            else "Содержимое отличается. Формат и длительность проверены, но это не доказывает, что выбрана та же песня. Подтверди выбор самостоятельно.",
                            color=if(preview.exact)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        Text("Создадим отдельную копию в Music/VK Music Export/Восстановленные и свяжем с этой записью. Выбранный файл и прежние файлы останутся на месте. Обложки добавляются отдельной кнопкой.")
                        if(preview.references>1)Text("Обновим все связи с этим файлом: ${preview.references}. Альбомы и плейлисты будут использовать выбранную копию.")
                        if(!preview.exact)Text("Это будет локальная замена. Она не подтверждает сохранность оригинала для удаления песни из ВК.")
                    }
                    item {Button(onClick=onConfirm,enabled=!state.running,modifier=Modifier.testTag("confirm_file_recovery")){Text("Восстановить копию")}}
                    item {TextButton(onClick=onBack,enabled=!state.running){Text("Отмена")}}
                }else if(forget!=null) {
                    item {
                        Text("Убрать устаревшую запись?",style=MaterialTheme.typography.titleMedium)
                        Text(forget.displayName)
                        Text("Файлы на телефоне и музыка ВК останутся. Приложение забудет эту сохранённую копию; при следующем скачивании может создать новую.")
                    }
                    item {OutlinedButton(onClick=onConfirmForget,enabled=!state.running,
                        border=BorderStroke(1.dp,MaterialTheme.colorScheme.error),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                        modifier=Modifier.testTag("confirm_forget_file")){Text("Убрать запись")}}
                    item {TextButton(onClick=onBack,enabled=!state.running){Text("Отмена")}}
                }else {
                    item {Text("Проверяем только файлы из учёта приложения. Автопоиск ищет единственное совпадение размера и SHA-256 среди доступных файлов приложения в Music. Название не используется для сопоставления.",style=MaterialTheme.typography.bodySmall)}
                    item {
                        OutlinedButton(onClick=onCheck,enabled=!state.running,modifier=Modifier.testTag("check_saved_files")){Text(if(state.resumable)"Продолжить проверку" else "Проверить файлы")}
                        if(state.problems.isNotEmpty())Button(onClick=onSearch,enabled=!state.running,modifier=Modifier.testTag("search_saved_files")){Text("Найти автоматически")}
                        Text("Файлов восстановлено: ${state.restored} · требуют внимания: ${state.problems.size}")
                    }
                    items(state.problems,key={it.row.taskKey}){problem->
                        Column(Modifier.testTag("saved_file_problem_${problem.row.taskKey}")) {
                            Text(problem.row.displayName,style=MaterialTheme.typography.titleSmall)
                            Text(problem.reason.label,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                            problem.searchReason?.let{Text(it.label,style=MaterialTheme.typography.bodySmall)}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick={onSelect(problem.row)},enabled=!state.running){Text("Выбрать файл")}
                                TextButton(onClick={onForget(problem.row)},enabled=!state.running){Text("Убрать запись")}
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.running)"Свернуть" else "Закрыть")}
                if(state.running)OutlinedButton(onClick=onStop){Text("Остановить")}
            }
        }
    }
}
