package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal val DownloadIcon=ImageVector.Builder("Downloads",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {
        moveTo(11f,3f);lineTo(13f,3f);lineTo(13f,12f);lineTo(17f,8f)
        lineTo(18.4f,9.4f);lineTo(12f,15.8f);lineTo(5.6f,9.4f)
        lineTo(7f,8f);lineTo(11f,12f);close()
        moveTo(5f,17f);lineTo(7f,17f);lineTo(7f,19f);lineTo(17f,19f)
        lineTo(17f,17f);lineTo(19f,17f);lineTo(19f,21f);lineTo(5f,21f);close()
    }
}.build()

@Composable
internal fun DownloadProgressContent(queue: ExportSnapshot,busy: Boolean=false,actionError: MusicError?=null,
    onPause: ()->Unit,onResume: ()->Unit,onRetry: ()->Unit,onClear: ()->Unit,
    onSourceRetry: (ExportSourceFailure)->Unit={},onExportReport: (()->Unit)?=null,scrollable: Boolean=false) {
    val working=queue.status in setOf("RUNNING","QUEUED")
    Column(if(scrollable)Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())else Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Скачивание музыки",style=MaterialTheme.typography.titleLarge)
        if(queue.account>0)Text("Аккаунт ВК: ${queue.account}",style=MaterialTheme.typography.bodySmall)
        else if(queue.total>0)Text("У старой очереди не указан аккаунт. При продолжении попросим подтвердить привязку.",style=MaterialTheme.typography.bodySmall)
        Text(when(queue.status) {
            "RUNNING"->"Скачивание продолжается"
            "QUEUED"->"Ожидание сети и запуска системой"
            "READY"->"Очередь готова к скачиванию"
            "FINISHED"->if(queue.sourceIssues>0||queue.failed>0)"Готово с пропусками" else "Готово"
            "FINISHED_WITH_ERRORS"->"Готово с пропусками"
            "EMPTY"->"Очередь скачивания пуста"
            else->"Приостановлено"
        },Modifier.testTag("downloads_status"))
        if(queue.total>0) {
            val processed=(queue.done+queue.failed).coerceIn(0,queue.total)
            Text("Сохранено: ${queue.done} из ${queue.total}",style=MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress={processed.toFloat()/queue.total},modifier=Modifier.fillMaxWidth().testTag("downloads_total_progress"))
            Text("Обработано: $processed/${queue.total} · ${processed.toLong()*100/queue.total}%",style=MaterialTheme.typography.bodySmall)
            Text("Осталось: ${queue.pending} · пропущено: ${queue.failed}")
            Text("С обложкой: ${queue.covered} · без добавленной обложки: ${queue.done-queue.covered}",style=MaterialTheme.typography.bodySmall)
        }
        if(queue.status=="RUNNING"&&queue.currentTask==null)queue.progress?.takeIf{it.stage=="checking_saved"}?.let{p->
            Text("Проверяем сохранённые файлы: ${p.segment}/${p.segments}",Modifier.testTag("downloads_saved_check"))
        }
        if(queue.status=="RUNNING")queue.currentTask?.let{task->
            Text("Текущий трек",style=MaterialTheme.typography.titleSmall)
            Text("${task.track.artist} — ${task.track.displayTitle}",Modifier.testTag("downloads_current_track"))
            queue.progress?.let{p->
                Text(when(p.stage) {
                    "artwork"->"Получение и добавление обложки"
                    "manifest"->"Чтение списка сегментов"
                    "segments"->"Сегменты: ${p.segment}/${p.segments} · получено ${p.bytes/1024} КБ"
                    "verifying"->"Проверка и сохранение файла · ${p.bytes/1024} КБ"
                    "checking_saved"->"Проверяем уже сохранённые файлы на телефоне"
                    else->"Получено ${p.bytes/1024} КБ"
                },Modifier.testTag("downloads_current_progress"),style=MaterialTheme.typography.bodySmall)
                if(p.stage=="segments"&&p.segments>0)LinearProgressIndicator(
                    progress={(p.segment.toFloat()/p.segments).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            }
        }
        queue.error?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
        actionError?.takeIf{it!=queue.error}?.let{Text("${it.label} Код: ${it.name}",color=MaterialTheme.colorScheme.error)}
        if(queue.sourceIssues>0)Text("Не прочитаны источники/подборки: ${queue.sourceIssues}. Экспорт всей библиотеки не подтверждён.",color=MaterialTheme.colorScheme.error)
        if(working)Button(enabled=!busy,onClick=onPause,modifier=Modifier.fillMaxWidth().testTag("downloads_pause")){Text("Пауза скачивания")}
        else if(queue.total>0||queue.sourceIssues>0) {
            if(queue.unfinished)Button(enabled=!busy,onClick=onResume,modifier=Modifier.fillMaxWidth().testTag("downloads_resume")){Text("Продолжить скачивание")}
            if(queue.failed>0)OutlinedButton(enabled=!busy,onClick=onRetry,modifier=Modifier.fillMaxWidth().testTag("downloads_retry")){Text("Повторить пропущенные треки")}
            TextButton(enabled=!busy,onClick=onClear,modifier=Modifier.fillMaxWidth().testTag("downloads_clear")){Text("Очистить очередь · файлы останутся")}
        }
        onExportReport?.let{export->if(queue.total>0||queue.sourceIssues>0) {
            OutlinedButton(onClick=export){Text("Поделиться полным отчётом")}
            Text("Отчёт содержит названия песен, источники, результаты и пути файлов. Токены и ссылки ВК не включаются.",style=MaterialTheme.typography.bodySmall)
        }}
        if(working||queue.unfinished)Text("После паузы незавершённый файл скачивается заново. Готовые треки сохраняются.",style=MaterialTheme.typography.bodySmall)
        if(working)Text("Панель можно свернуть — скачивание продолжится. Android может приостановить задачу; продолжение доступно здесь.",style=MaterialTheme.typography.bodySmall)
    }
}
