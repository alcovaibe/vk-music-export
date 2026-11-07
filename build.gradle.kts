// Patch the Gradle plugin classpath itself; application constraints do not affect it.
buildscript {
    configurations.getByName("classpath").resolutionStrategy.eachDependency {
        val patched = when {
            requested.group == "com.google.guava" && requested.name == "guava" -> "33.7.2-jre"
            requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> "3.18.0"
            requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> "0.9.6"
            requested.group == "org.bouncycastle" && requested.name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on") -> "1.85"
            requested.group == "org.jdom" && requested.name == "jdom2" -> "2.0.6.1"
            else -> null
        }
        if (patched != null) {
            useVersion(patched)
            because("Patch OSV findings in the resolved AGP classpath; see the current audit matrix")
        }
    }
}

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
