package app.alcovaibe.vkmusicexport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled notices remain available offline, before granting access to VK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegalInformationSheet(page: String,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val text by produceState("Загрузка…",page) {
        value=withContext(Dispatchers.IO){runCatching {
            context.assets.open("legal/$page.txt").bufferedReader().use{it.readText()}
        }.getOrDefault("Не удалось прочитать документ. Он также доступен в репозитории alcovaibe/vk-music-export.")}
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        modifier=Modifier.testTag("legal_sheet")) {
        Column(Modifier.fillMaxWidth().weight(1f,false).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(page=="privacy")"Данные и доступ к ВК" else "Лицензии компонентов",style=MaterialTheme.typography.titleLarge)
            Text(text,style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick=onDismiss,modifier=Modifier.align(androidx.compose.ui.Alignment.End).padding(16.dp)){Text("Закрыть")}
    }
}
