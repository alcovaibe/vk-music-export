package app.alcovaibe.vkmusicexport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main app dependencies and their license notices, matching THIRD_PARTY_NOTICES.md.
 * The complete Apache-2.0 text is bundled with the APK for offline access.
 */
private data class ThirdPartyComponent(val id: String,val title: String,val owner: String)

private val thirdPartyComponents = listOf(
    ThirdPartyComponent("kotlin","Kotlin","JetBrains / Kotlin contributors"),
    ThirdPartyComponent("compose","Jetpack Compose","Android Open Source Project / contributors"),
    ThirdPartyComponent("material3","Material 3","Android Open Source Project / contributors"),
    ThirdPartyComponent("activity","AndroidX Activity","Android Open Source Project / contributors"),
    ThirdPartyComponent("lifecycle","AndroidX Lifecycle","Android Open Source Project / contributors"),
    ThirdPartyComponent("browser","AndroidX Browser","Android Open Source Project / contributors"),
    ThirdPartyComponent("media3","AndroidX Media3","Android Open Source Project / Media3 contributors"),
    ThirdPartyComponent("okhttp","OkHttp","Square / contributors"),
    ThirdPartyComponent("okio","Okio","Square / contributors"),
    ThirdPartyComponent("coroutines","Kotlin Coroutines","JetBrains / contributors"),
    ThirdPartyComponent("serialization","Kotlin Serialization","JetBrains / contributors"),
    ThirdPartyComponent("guava","Guava","Google / Guava contributors"),
    ThirdPartyComponent("compose_icons","Compose Material Icons","Android Open Source Project / contributors")
)

@Composable
internal fun ThirdPartyComponentsPage(onBack: ()->Unit,onComponent: (String)->Unit) {
    LazyAppPage("Сторонние компоненты",onBack,tag="license_components_screen") {
        item("components") {
            Surface(modifier=Modifier.fillMaxWidth().testTag("license_components_card"),
                shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Column {
                    thirdPartyComponents.forEachIndexed { index,component ->
                        TextButton(onClick={onComponent(component.id)},
                            modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)
                                .testTag("license_component_${component.id}")) {
                            Text(component.title,Modifier.fillMaxWidth().padding(horizontal=8.dp),
                                fontWeight=FontWeight.SemiBold)
                        }
                        if(index<thirdPartyComponents.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal=16.dp),
                                color=MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThirdPartyComponentDetailPage(id: String?,onBack: ()->Unit) {
    val component=thirdPartyComponents.firstOrNull{it.id==id}
    val context=LocalContext.current
    val license by produceState("Загрузка…",component?.id) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("legal/apache_2_0.txt").bufferedReader().use{it.readText()}
            }.getOrDefault("Не удалось прочитать текст лицензии.")
        }
    }
    AppPage(component?.title?:"Сторонний компонент",onBack,tag="license_component_detail_screen") {
        if(component==null) {
            Text("Компонент не найден.",style=MaterialTheme.typography.bodyMedium)
        }else {
            Text("Правообладатели: ${component.owner}",
                style=MaterialTheme.typography.bodyMedium)
            Text("Лицензия: Apache-2.0",style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(license,Modifier.fillMaxWidth().testTag("license_component_license_text"),
                style=MaterialTheme.typography.bodySmall)
        }
    }
}
