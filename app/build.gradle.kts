import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val buildVersionCode = providers.environmentVariable("APP_VERSION_CODE")
    .orElse(appVersion.getProperty("versionCode")).get().toInt()
require(buildVersionCode in 1..2_100_000_000) { "Invalid Android versionCode" }
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull

android {
    namespace = "app.alcovaibe.vkmusicexport"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.alcovaibe.vkmusicexport"
        minSdk = 36
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseKeystore != null) create("releaseKey") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("releaseKey")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("Fix GHSA-xxph-c9ww-hj94 / CVE-2026-102554 in transitive Guava")
        }
    }
    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.media3.extractor)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

dependencyLocking { lockAllConfigurations() }

configurations.configureEach {
    if(name == "androidLintTool") resolutionStrategy.eachDependency {
        val patched = when {
            requested.group == "com.google.guava" && requested.name == "guava" -> "33.7.2-jre"
            requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> "3.18.0"
            requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> "4.5.14"
            requested.group == "org.bouncycastle" && requested.name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on") -> "1.85"
            else -> null
        }
        if(patched != null) {
            useVersion(patched)
            because("Patch OSV findings in the lint tool classpath; see docs/AUDIT-FIXES.md")
        }
    }
}

tasks.register("writeDependencyInventory") {
    description = "Resolve build/test classpaths and write the exact Maven inventory for SBOM/SCA"
    doLast {
        val names = setOf("debugCompileClasspath", "debugRuntimeClasspath", "releaseCompileClasspath",
            "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath", "releaseUnitTestRuntimeClasspath",
            "debugAndroidTestRuntimeClasspath", "androidLintTool")
        val coordinates = configurations.filter { it.isCanBeResolved && it.name in names }
            .flatMap { configuration -> configuration.incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { id ->
                    "${id.group}:${id.module}:${id.version}"
                }
            } }.toSortedSet()
        check(coordinates.isNotEmpty()) { "Dependency inventory is empty" }
        layout.buildDirectory.file("reports/dependencies/resolved.txt").get().asFile.apply {
            parentFile.mkdirs(); writeText(coordinates.joinToString("\n", postfix = "\n"))
        }
    }
}
