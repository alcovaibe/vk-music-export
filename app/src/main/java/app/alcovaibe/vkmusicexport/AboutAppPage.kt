package app.alcovaibe.vkmusicexport

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutAppPage(onBack: ()->Unit) {
    var legalPage by rememberSaveable {mutableStateOf<String?>(null)}
    when(legalPage) {
        "licenses" -> LicenseMenuPage(onBack={legalPage=null},
            onApplication={legalPage="app_license"},onComponents={legalPage="components"})
        "app_license" -> LegalInformationPage("Лицензия приложения","app_license","license_app_screen",
            onBack={legalPage="licenses"})
        "components" -> LegalInformationPage("Сторонние компоненты","components","license_components_screen",
            onBack={legalPage="licenses"})
        else -> {
            AppPage("О приложении",onBack,tag="about_app_screen") {
                Column(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(shape=RoundedCornerShape(20.dp),color=androidx.compose.ui.graphics.Color.Black) {
                        Image(painterResource(R.drawable.ic_launcher_foreground),null,Modifier.size(80.dp))
                    }
                    Text("VK Music Export",style=MaterialTheme.typography.headlineSmall)
                    Text("Версия ${BuildConfig.VERSION_NAME}",Modifier.testTag("app_version"),
                        style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Column {
                        TextButton(onClick={legalPage="licenses"},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("open_licenses")) {
                            Text("Лицензия",Modifier.fillMaxWidth().padding(horizontal=8.dp))
                        }
                        HorizontalDivider(Modifier.padding(horizontal=16.dp))
                        TextButton(onClick={legalPage="privacy"},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("open_privacy")) {
                            Text("Данные и доступ",Modifier.fillMaxWidth().padding(horizontal=8.dp))
                        }
                    }
                }
            }
            // Keep the existing privacy sheet and its dismissal behavior unchanged.
            if(legalPage=="privacy")LegalInformationSheet("privacy"){legalPage=null}
        }
    }
}

/** Both license options share one card and the AboutAppPage row dimensions. */
@Composable
internal fun LicenseMenuPage(onBack: ()->Unit,onApplication: ()->Unit,onComponents: ()->Unit) {
    AppPage("Лицензия",onBack,tag="license_menu_screen") {
        Surface(modifier=Modifier.fillMaxWidth().testTag("license_menu_card"),
            shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
            Column {
                TextButton(onClick=onApplication,
                    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("license_app_item")) {
                    Text("Лицензия приложения",Modifier.fillMaxWidth().padding(horizontal=8.dp),
                        fontWeight=FontWeight.SemiBold)
                }
                HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick=onComponents,
                    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("license_components_item")) {
                    Text("Сторонние компоненты",Modifier.fillMaxWidth().padding(horizontal=8.dp),
                        fontWeight=FontWeight.SemiBold)
                }
            }
        }
    }
}
