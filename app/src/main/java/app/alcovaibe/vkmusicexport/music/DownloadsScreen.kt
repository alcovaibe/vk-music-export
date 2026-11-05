package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun downloadStatus(queue: ExportSnapshot)=when(queue.status) {
    "RUNNING"->"Скачивание продолжается"
    "QUEUED"->"Ожидание сети и запуска системой"
    "READY"->"Очередь готова к скачиванию"
    "FINISHED"->if(queue.sourceIssues>0||queue.failed>0)"Готово с пропусками" else "Готово"
    "FINISHED_WITH_ERRORS"->"Готово с пропусками"
    "EMPTY"->"Очередь скачивания пуста"
    else->"Приостановлено"
}
internal fun downloadProgressText(p: AudioProgress)=when(p.stage) {
    "artwork"->"Получение и добавление обложки"
    "manifest"->"Чтение списка сегментов"
    "segments"->"Сегменты: ${p.segment}/${p.segments} · получено ${p.bytes/1024} КБ"
    "verifying"->"Проверка и сохранение файла · ${p.bytes/1024} КБ"
    "checking_saved"->"Проверяем сохранённые файлы: ${p.segment}/${p.segments}"
    else->"Получено ${p.bytes/1024} КБ"
}
internal fun downloadFraction(p: AudioProgress?)=p?.takeIf{it.stage=="segments"&&it.segments>0}?.let{(it.segment.toFloat()/it.segments).coerceIn(0f,1f)}

internal fun LazyListScope.downloadsItems(queue: ExportSnapshot,pageState: DownloadsPageState,tab: DownloadsTab,busy: Boolean,
    actionError: MusicError?,onTab:(DownloadsTab)->Unit,onPage:(Int)->Unit,onReload:()->Unit,
    onPause:()->Unit,onResume:()->Unit,onRetry:()->Unit,onClear:()->Unit,onReport:()->Unit,
    onSourceRetry:(ExportSourceFailure,String)->Unit,onOpenSaved:(PublishedAudio)->Unit,canOpenSaved: Boolean) {
    val working=queue.status in setOf("RUNNING","QUEUED")
    val current=queue.currentTask.takeIf{queue.status=="RUNNING"}
    val page=pageState.page?.takeIf{it.queueId==queue.queueId&&it.tab==tab}
    val currentPage=page!=null&&page.revision>=queue.revision&&!pageState.loading
    item("downloads_summary") {
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(downloadStatus(queue),Modifier.testTag("downloads_status"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                if(queue.total>0) {
                    Text("${queue.done} из ${queue.total} треков сохранено",style=MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(progress={queue.done.toFloat()/queue.total},modifier=Modifier.fillMaxWidth().testTag("downloads_total_progress"))
                    Text("${(queue.pending-if(current!=null)1 else 0).coerceAtLeast(0)} ожидают · ${queue.failed+queue.sourceIssues} проблем",style=MaterialTheme.typography.bodyMedium)
                    Text("Обработано: ${queue.done+queue.failed}/${queue.total} · с обложкой: ${queue.covered}",style=MaterialTheme.typography.bodySmall)
                }
                if(queue.account>0)Text("Аккаунт ВК: ${queue.account}",style=MaterialTheme.typography.bodySmall)
                else if(queue.total>0)Text("Для продолжения старой очереди нужно подтвердить аккаунт.",style=MaterialTheme.typography.bodySmall)
                if(working)Button(onClick=onPause,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("downloads_pause")) {
                    Icon(FriendPauseIcon,null);Spacer(Modifier.width(8.dp));Text("Приостановить")
                }else if(queue.unfinished)Button(onClick=onResume,enabled=!busy,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("downloads_resume")) {
                    Icon(PlayerPlayIcon,null);Spacer(Modifier.width(8.dp));Text("Продолжить скачивание")
                }
                if(!working&&queue.failed>0)OutlinedButton(onClick=onRetry,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("downloads_retry")){Text("Повторить пропущенные треки")}
                queue.error?.let{Text(it.label,color=MaterialTheme.colorScheme.error)}
                actionError?.takeIf{it!=queue.error}?.let{Text(it.label,color=MaterialTheme.colorScheme.error)}
                if(queue.sourceIssues>0)Text("Не прочитаны источники/подборки: ${queue.sourceIssues}. Экспорт всей библиотеки не подтверждён.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    item("downloads_filters") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            DownloadsTab.entries.forEach{choice->
                val selected=tab==choice
                val count=when(choice){DownloadsTab.QUEUE->queue.pending;DownloadsTab.ERRORS->queue.failed+queue.sourceIssues;DownloadsTab.DONE->queue.done}
                Surface(onClick={onTab(choice)},shape=RoundedCornerShape(18.dp),color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor=if(selected)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.weight(1f).heightIn(min=76.dp).testTag("downloads_tab_${choice.name}")) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Icon(when(choice){DownloadsTab.QUEUE->FriendQueueIcon;DownloadsTab.ERRORS->FriendErrorIcon;DownloadsTab.DONE->Icons.Default.CheckCircle},null)
                        Text(choice.title,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelLarge)
                        Text(count.toString(),style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if(tab==DownloadsTab.QUEUE&&current!=null)item("downloads_active") {
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Сейчас скачивается",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    MusicThumbnail(null,size=52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(current.track.displayTitle,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("downloads_current_track"))
                        Text(current.track.artist,style=MaterialTheme.typography.bodySmall)
                    }
                }
                val fraction=downloadFraction(queue.progress)
                if(fraction==null)LinearProgressIndicator(Modifier.fillMaxWidth())else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
                queue.progress?.let{Text(downloadProgressText(it),Modifier.testTag("downloads_current_progress"),style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
    if(tab==DownloadsTab.QUEUE&&current==null&&queue.status=="RUNNING")queue.progress?.takeIf{it.stage=="checking_saved"}?.let{progress->
        item("downloads_saved_check"){Text(downloadProgressText(progress),Modifier.testTag("downloads_saved_check"),style=MaterialTheme.typography.bodySmall)}
    }
    item("downloads_list_title"){Text(when(tab){DownloadsTab.QUEUE->if(current==null)"В очереди" else "Далее в очереди";DownloadsTab.ERRORS->"Проблемы скачивания";DownloadsTab.DONE->"Сохранённые треки"},style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
    if(pageState.error)item("downloads_read_error") {Column {
        Text("Не удалось прочитать список. Очередь и файлы сохранены.",color=MaterialTheme.colorScheme.error)
        TextButton(onClick=onReload,modifier=Modifier.testTag("downloads_reload")){Text("Повторить чтение")}
    }}else if(page==null)item("downloads_loading"){Text("Загружаем список…",Modifier.testTag("downloads_loading"))}
    if(page!=null) {
        items(page.sources,key={"source_${it.source.key}"}){issue->
            Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(issue.title,fontWeight=FontWeight.Bold);Text(issue.error.label,color=MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick={onSourceRetry(issue,page.queueId)},enabled=!working&&!busy&&currentPage,modifier=Modifier.testTag("downloads_retry_source")){Text("Перечитать источник")}
                }
            }
        }
        items(page.rows.filterNot{tab==DownloadsTab.QUEUE&&it.task.key==current?.key},key={"queue_${it.task.key}"}){row->
            DownloadTaskCard(row,onOpen=if(tab==DownloadsTab.DONE&&canOpenSaved)row.file?.let{file->{onOpenSaved(file)}}else null)
        }
        if(page.total==0)item("downloads_empty_list"){Text(when(tab){DownloadsTab.QUEUE->"Ожидающих треков нет";DownloadsTab.ERRORS->"Подробных ошибок нет";DownloadsTab.DONE->"Здесь появятся завершённые скачивания"},style=MaterialTheme.typography.bodyMedium)}
        if(tab==DownloadsTab.ERRORS&&(queue.sourceIssues-(page.total-queue.failed))>0)item("downloads_legacy_problems"){Text("Для части непрочитанных источников подробности недоступны. Полное сохранение библиотеки не подтверждено.",style=MaterialTheme.typography.bodySmall)}
        if(page.total>50)item("downloads_pagination") {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick={onPage((page.offset-50).coerceAtLeast(0))},enabled=page.offset>0&&!pageState.loading,modifier=Modifier.testTag("downloads_previous_page")){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Предыдущая страница")}
                Text("${page.offset+1}–${minOf(page.offset+50,page.total)} из ${page.total}",Modifier.testTag("downloads_page_counter"),style=MaterialTheme.typography.bodySmall)
                IconButton(onClick={onPage(page.offset+50)},enabled=page.offset+50<page.total&&!pageState.loading,modifier=Modifier.testTag("downloads_next_page")){Icon(Icons.AutoMirrored.Filled.ArrowForward,"Следующая страница")}
            }
        }
    }
    item("downloads_actions") {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Можно слушать музыку, пока идёт скачивание.",style=MaterialTheme.typography.bodySmall)
        if(queue.total>0||queue.sourceIssues>0) {
            OutlinedButton(onClick=onReport,modifier=Modifier.fillMaxWidth().testTag("downloads_export_report")){Text("Поделиться полным отчётом")}
            Text("Отчёт содержит названия песен, источники, результаты и пути файлов. Токены и ссылки ВК не включаются.",style=MaterialTheme.typography.bodySmall)
            if(!working)TextButton(onClick=onClear,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("downloads_clear")){Text("Очистить очередь · файлы останутся")}
        }
    }}
}

@Composable
private fun DownloadTaskCard(row: ExportQueueRow,onOpen:(()->Unit)?) {
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,
        modifier=Modifier.fillMaxWidth().testTag("download_task_${row.task.key}").then(if(onOpen==null)Modifier else Modifier.clickable(onClickLabel="Воспроизвести ${row.task.track.displayTitle}",role=Role.Button,onClick=onOpen))) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                MusicThumbnail(null,uri=row.file?.uri,identity=row.file?.let{it.uri+it.sha256}?:row.task.key,size=48.dp)
                Column(Modifier.weight(1f)) {
                    Text(row.task.track.displayTitle,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(row.task.track.artist,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text(when(row.state){"DONE"->"Сохранён";"FAILED"->"Ошибка";"RUNNING"->"Скачивается";else->"Ожидает"},style=MaterialTheme.typography.labelSmall,color=if(row.state=="FAILED")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.error?.let{Text(it.label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            row.details?.let{Text(it.label,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
