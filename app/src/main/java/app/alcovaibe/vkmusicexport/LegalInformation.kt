package app.alcovaibe.vkmusicexport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled notices remain available offline, before granting access to VK. */
@Composable
internal fun LegalInformationLinks() {
    var selected by remember{mutableStateOf<String?>(null)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={selected="privacy"}){Text("Данные и доступ")}
        TextButton(onClick={selected="licenses"}){Text("Лицензии")}
    }
    selected?.let{page->
        val context=LocalContext.current
        val text by produceState("Загрузка…",page) {
            value=withContext(Dispatchers.IO){runCatching {
                context.assets.open("legal/$page.txt").bufferedReader().use{it.readText()}
            }.getOrDefault("Не удалось прочитать документ. Он также доступен в репозитории alcovaibe/vk-music-export.")}
        }
        AlertDialog(onDismissRequest={selected=null},
            title={Text(if(page=="privacy")"Данные и доступ к ВК" else "Лицензии компонентов")},
            text={Text(text,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},
            confirmButton={TextButton(onClick={selected=null}){Text("Закрыть")}})
    }
}
