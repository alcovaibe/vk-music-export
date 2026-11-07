plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Plugin DSL classpath is separate from the application/runtime graph.
tasks.register("writeBuildToolInventory") {
    doLast {
        val pluginCoordinates = buildscript.configurations.getByName("classpath")
            .incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { id ->
                    "${id.group}:${id.module}:${id.version}"
                }
            }.toSortedSet()
        val settingsCoordinates = gradle.extra["settingsPluginCoordinates"] as List<*>
        check(pluginCoordinates.isNotEmpty() && settingsCoordinates.isNotEmpty()) { "Incomplete plugin inventory" }
        layout.buildDirectory.file("reports/dependencies/build-tools.txt").get().asFile.apply {
            parentFile.mkdirs()
            writeText((pluginCoordinates + settingsCoordinates.map { it.toString() }).toSortedSet().joinToString("\n", postfix = "\n"))
        }
    }
}
