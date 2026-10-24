import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "pt.tiago.nightfleet"
    // API 37.2: the same platform the series already builds on F-Droid (Sopa de Letras).
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "pt.tiago.nightfleet"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Release signing comes from environment variables (CI) or ~/.gradle/gradle.properties, never
    // from the repository. Without credentials the release stays unsigned, which is how F-Droid builds it.
    val releaseStoreFile = providers.environmentVariable("SIGNING_KEYSTORE_FILE")
        .orElse(providers.gradleProperty("nightFleet.release.storeFile")).orNull
    val releaseStorePassword = providers.environmentVariable("SIGNING_PASSWORD")
        .orElse(providers.gradleProperty("nightFleet.release.storePassword")).orNull
    val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
        .orElse(providers.gradleProperty("nightFleet.release.keyAlias")).orNull
    // -PnightFleet.release.unsigned=true builds the unsigned release even with credentials (tools/repro_check.sh).
    val forceUnsigned = providers.gradleProperty("nightFleet.release.unsigned").orNull == "true"
    val hasReleaseSigning = !forceUnsigned && releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(releaseStoreFile!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = providers.gradleProperty("nightFleet.release.keyPassword").orNull ?: releaseStorePassword
        }
    }
    buildTypes {
        release {
            // R8 is deterministic for the same inputs. Rules: app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Keep the git revision out of the APK so release builds are reproducible from source.
            vcsInfo.include = false
        }
    }
    // The dependency metadata block is encrypted for Google Play only; F-Droid rejects it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // Keep native libraries unstripped so the APK is identical with or without the NDK installed.
    packaging { jniLibs { keepDebugSymbols += "**/*.so" } }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("generated/fleetAssets").get().asFile.path)
    // Debug only: the golden moves for the on-device checks (DebugChecks). The release sources never see this directory.
}

/** Copies the golden moves (the file the JVM tests compare with) into the debug assets. Debug builds only. */
abstract class CopyGoldenMoves : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(File(outputDir.get().asFile, "diagnostics/golden-moves.txt"), overwrite = true)
    }
}

// One file for the JVM tests and the device: core/src/test/resources. The release variant never sees it.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val copyGolden = tasks.register<CopyGoldenMoves>("copyDebugGoldenMoves") {
            source.set(rootProject.layout.projectDirectory.file("core/src/test/resources/pt/tiago/nightfleet/core/ai/golden-moves.txt"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyGolden, CopyGoldenMoves::outputDir)
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.datastore)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

// The packaged catalog is exported by :leveltool once content exists.
if (rootProject.file("content/catalog.json").exists()) {
    tasks.named("preBuild") { dependsOn(":leveltool:exportGameAssets") }
}
