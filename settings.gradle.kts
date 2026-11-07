pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VKMusicExport"
include(":app")

// Capture the resolved settings-plugin graph while the Settings ScriptHandler owns it.
gradle.extra["settingsPluginCoordinates"] = buildscript.configurations.getByName("classpath")
    .incoming.resolutionResult.allComponents.mapNotNull {
        (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { id ->
            "${id.group}:${id.module}:${id.version}"
        }
    }.toSortedSet().toList()
