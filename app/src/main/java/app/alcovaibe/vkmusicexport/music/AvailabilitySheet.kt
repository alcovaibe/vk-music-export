package app.alcovaibe.vkmusicexport.music

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
internal fun AvailabilitySheet(state: AvailabilityState,onCheck:()->Unit,onStop:()->Unit,onDismiss:()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("availability_sheet")) {
            Text("Доступность треков ВК",style=MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=460.dp).padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item{Text("Проверим точные ID пропущенных записей и ограничения в библиотеке. Аудио не скачивается. Используется текущий аккаунт и соединение, в том числе за пределами России и Беларуси. Страна регистрации профиля неизвестна приложению и сама по себе не подтверждает доступ.",style=MaterialTheme.typography.bodySmall)}
                item{Text("После смены аккаунта или сети/VPN запусти проверку заново. Если ВК вернёт доступный поток, повтори пропущенные скачивания. Если запись не возвращена, удаление или региональную блокировку по этому ответу отличить нельзя.",style=MaterialTheme.typography.bodySmall)}
                if(state.running)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Проверено: ${state.done}/${state.total}")}
                state.message?.let{item{Text(it)}}
                if(state.results.isNotEmpty())item{Text("Ссылка доступна: ${state.results.count{it.linkAvailable}} · не подтверждены: ${state.results.count{!it.linkAvailable}}")}
                items(state.results,key={it.track.id}){row->Column{
                    Text(row.track.displayTitle,style=MaterialTheme.typography.titleSmall);Text(row.track.artist)
                    Text(row.reason,style=MaterialTheme.typography.bodySmall)
                }}
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onDismiss){Text("Закрыть")}
                Button(onClick=if(state.running)onStop else onCheck,modifier=Modifier.weight(1f).testTag("availability_check")){
                    Text(if(state.running)"Остановить" else "Проверить текущий доступ")
                }
            }
        }
    }
}
