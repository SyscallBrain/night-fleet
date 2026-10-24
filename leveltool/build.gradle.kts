plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
application { mainClass.set("pt.tiago.nightfleet.leveltool.MainKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    // The pipeline tests read content/ from the project root, like the run task does.
    workingDir = rootProject.projectDir
}

// Content pipeline: the build validates the catalog, replays every certificate and only then
// certificates are replayed separately by verifyCertificates.
val contentRoot = rootProject.layout.projectDirectory.dir("content")
val assetOutput = rootProject.layout.projectDirectory.dir("app/build/generated/fleetAssets")

// `-PnightFleet.partialContent=true`: a partial catalog (fewer than 12 seas published, or no daily and endless
// packs yet) is accepted. Every other error still fails the build; the default is the full strict campaign.
val partialContentArgs: List<String> =
    if (findProperty("nightFleet.partialContent")?.toString() == "true") listOf("--partial") else emptyList()

val validateCatalog = tasks.register<JavaExec>("validateCatalog") {
    group = "verification"
    description = "Validates the published catalog (structure, rules, counts)."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    args(listOf("validate", "--root", "content", "--strict") + partialContentArgs)
    inputs.dir(contentRoot)
    outputs.upToDateWhen { false }
}

val verifyCertificates = tasks.register<JavaExec>("verifyCertificates") {
    group = "verification"
    description = "Replays every certificate with the current engine."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    args("verify-certificates", "--root", "content")
    inputs.dir(contentRoot)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("exportGameAssets") {
    group = "build"
    description = "Validates and exports the catalog (fast, deterministic; certificates are replayed by verifyCertificates in verify.sh/CI) into Android assets with a sha256 manifest."
    dependsOn(validateCatalog)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    args(listOf("export-assets", "--root", "content", "--output", assetOutput.asFile.absolutePath) + partialContentArgs)
    // Up to date when neither the content nor the tool changed: no timestamps, identical bytes each run.
    inputs.dir(contentRoot)
    inputs.files(sourceSets.main.get().runtimeClasspath)
    outputs.dir(assetOutput)
}
