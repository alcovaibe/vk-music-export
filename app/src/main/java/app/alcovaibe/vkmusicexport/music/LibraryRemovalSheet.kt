package app.alcovaibe.vkmusicexport.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryRemovalSheet(state: LibraryRemovalState,onConfirm: ()->Unit,onDismiss: ()->Unit) {
    if(!state.visible)return
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).testTag("library_removal_sheet")) {
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(state.phase==LibraryRemovalPhase.CONFIRM)"Удалить из «Моей музыки» ВК?" else "Удаление из ВК",style=MaterialTheme.typography.titleLarge)
                state.track?.let{Text("${it.artist} — ${it.displayTitle}")}
                when(state.phase) {
                    LibraryRemovalPhase.CONFIRM -> Text("Трек будет удалён из твоей музыки ВК.")
                    LibraryRemovalPhase.SENDING->{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Отправляем запрос в ВК…")}
                    else->state.message?.let{Text(it,Modifier.testTag("library_removal_result"))}
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDismiss){Text(if(state.phase==LibraryRemovalPhase.CONFIRM)"Отмена" else "Закрыть")}
                if(state.phase==LibraryRemovalPhase.CONFIRM)OutlinedButton(onClick=onConfirm,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.error),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                    modifier=Modifier.testTag("confirm_remove_from_vk")){Text("Удалить из ВК")}
            }
        }
    }
}
