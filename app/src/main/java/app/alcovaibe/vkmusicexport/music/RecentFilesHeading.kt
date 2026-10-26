package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentFilesHeading(enabled: Boolean=true,onClear: ()->Unit) {
    var confirm by rememberSaveable{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("Последние сохранённые файлы",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
        IconButton(enabled=enabled,onClick={confirm=true},modifier=Modifier.testTag("clear_recent_files")) {
            Icon(Icons.Default.Delete,"Очистить список последних файлов")
        }
    }
    if(confirm)ModalBottomSheet(onDismissRequest={confirm=false},
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)
            .padding(bottom=24.dp).testTag("clear_recent_files_sheet"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Очистить список последних сохранённых файлов?",style=MaterialTheme.typography.titleLarge)
            Text("Сами файлы останутся на устройстве.")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp,Alignment.End)) {
                TextButton(onClick={confirm=false}){Text("Отмена")}
                OutlinedButton(enabled=enabled,onClick={onClear();confirm=false},
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.error),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                    modifier=Modifier.testTag("confirm_clear_recent_files")) {Text("Удалить")}
            }
        }
    }
}
