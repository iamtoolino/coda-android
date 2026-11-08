import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val qaProperties = Properties().apply {
    val file = File(System.getProperty("user.home"), ".coda-dev.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
// Release signing is read from outside the repo and only when present.
val releaseSigningProperties = Properties().apply {
    val file = File(System.getProperty("user.home"), ".coda-release.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun qaLiteral(key: String): String = "\"" + qaProperties.getProperty(key, "")
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

val gitBuild = dev.coda.buildinfo.GitBuildInfo { args ->
    providers.exec {
        workingDir(rootDir)
        commandLine(listOf("git") + args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get()
}
fun buildLiteral(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.coda.android"
    compileSdk = 36
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // Isolate device acceptance when other worktrees use the shared emulator.
        applicationId = providers.gradleProperty("coda.qaApplicationId").getOrElse("dev.coda.android")
        minSdk = 36
        targetSdk = 36
        testInstrumentationRunner = "dev.coda.android.CodaTestRunner"
        versionCode = 28
        versionName = "1.0.0-rc3"
        buildConfigField("String", "GIT_COMMIT", buildLiteral(gitBuild.commit))
        buildConfigField("String", "GIT_DESCRIBE", buildLiteral(gitBuild.describe))
        buildConfigField("String", "GIT_TAG", buildLiteral(gitBuild.tag))
        buildConfigField("String", "GIT_COUNT", buildLiteral(gitBuild.count))
        ndk { abiFilters += "arm64-v8a" }
        // Android Auto lists every installed build with the media-browser action and car meta-data, all with the
        // same icon, and keeps a stale entry after a test build is uninstalled. Test builds (coda.qaApplicationId)
        // opt in with -Pcoda.qaAuto=true; the PO's build always registers. Explicit binds (tests, Media3) still work.
        // Keyed on the resulting id: the PO's dev.coda.android is always listed, however it is built.
        val autoListed = applicationId == "dev.coda.android" || providers.gradleProperty("coda.qaAuto").orNull == "true"
        manifestPlaceholders["autoBrowseAction"] =
            if (autoListed) "android.media.browse.MediaBrowserService" else "dev.coda.android.qa.MEDIA_BROWSER_NOT_LISTED"
        manifestPlaceholders["autoCarAppMetaData"] =
            if (autoListed) "com.google.android.gms.car.application" else "dev.coda.android.qa.car.application.not_listed"
        // Media3's MediaButtonReceiver enables Android's playback resumption (resume card, Bluetooth Play); it
        // makes a build a resumption candidate for SystemUI, so test builds get it only together with the Auto listing.
        manifestPlaceholders["mediaButtonReceiverEnabled"] = autoListed.toString()
        buildConfigField("boolean", "AUTO_LISTED", autoListed.toString())
    }

    buildFeatures { compose = true; buildConfig = true; resValues = true }
    signingConfigs {
        if (releaseSigningProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = File(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias", "coda")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            resValue("string", "app_name", providers.gradleProperty("coda.qaLabel").getOrElse("Coda"))
            listOf("serverUrl", "username", "password").forEach { key ->
                buildConfigField("String", "QA_" + key.uppercase(), qaLiteral("coda.qa.$key"))
            }
        }
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
        resValue("string", "app_name", providers.gradleProperty("coda.qaLabel").getOrElse("Coda test FX-3"))
    }
    sourceSets.getByName("test").java.srcDir("../buildSrc/src/main/java")
    // Pure helpers of the device-test settings gate, unit-tested on the JVM and used by the androidTest SystemSettings.
    sourceSets.getByName("test").kotlin.directories.add("src/deviceTestShared/kotlin")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/deviceTestShared/kotlin")
    sourceSets.getByName("benchmark").kotlin.directories.add("src/release/kotlin")
    // Battery diagnostics (log, counters, "Export battery log") in debug and preview builds only. A preview is a
    // release build whose versionName carries "-pre" (prerelease/* branches); any other release, and benchmark, compile
    // the inline no-op stub, so no diagnostics class, string or provider ships. -Pcoda.batteryLog=true|false overrides
    // the release choice (the release check in docs/reports/BAT-1a.md builds both).
    val batteryLogInRelease = providers.gradleProperty("coda.batteryLog").orNull?.toBoolean()
        ?: defaultConfig.versionName.orEmpty().contains("-pre")
    for ((type, on) in listOf("debug" to true, "release" to batteryLogInRelease, "benchmark" to false)) {
        sourceSets.getByName(type).kotlin.directories.add(if (on) "src/batteryLog/kotlin" else "src/batteryLogOff/kotlin")
        // Releases without the battery log get App info's "Manage space" (src/batteryLogOff/AndroidManifest.xml).
        // Not benchmark: srcFile would replace src/benchmark/AndroidManifest.xml and its <profileable>.
        if (type != "benchmark") sourceSets.getByName(type).manifest.srcFile(if (on) "src/batteryLog/AndroidManifest.xml" else "src/batteryLogOff/AndroidManifest.xml")
    }
    // Long synthetic tracks for the battery run (tools/bat-1a/generate.sh; generated, never committed).
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/bat1a-assets").get().asFile)
    // Reuse the Swift sRGB normalization as an independent P3 decode oracle.
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/pf4-assets").get().asFile)
    sourceSets.getByName("androidTest").resources.srcDir("../core/src/test/resources")
    testOptions.unitTests.all {
        it.systemProperty("codaQaAuto", providers.gradleProperty("coda.qaAuto").getOrElse("false"))
        it.systemProperty("releaseBuildConfig", layout.buildDirectory.file("generated/source/buildConfig/release/dev/coda/android/BuildConfig.java").get().asFile.absolutePath)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":store")) // Resolves :store's Android variant (AndroidSQLiteDriver).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.okhttp)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.datastore.preferences) // Settings
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation(files("libs/media3-ffmpeg-mp3-1.11.1.aar"))
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation("org.json:json:20240303") // RecentServers encoding (android.jar only has stubs).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose's accessibility checks (ATF) in the per-screen semantics test; test-only, Apache-2.0.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// The JVM regression reads the generated release source, never the debug credentials.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn("generateReleaseBuildConfig") }

// Input inventory for tools/fx-3/generate-notices.py; never includes developer properties.
tasks.register("licenceInventory") {
    val runtime = configurations.named("releaseRuntimeClasspath")
    val destination = layout.buildDirectory.file("licences/runtime-artifacts.txt")
    doLast {
        val output = destination.get().asFile
        output.parentFile.mkdirs()
        output.writeText(runtime.get().resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
            .joinToString("\n") { "${it.moduleVersion.id}|${it.file.absolutePath}" })
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.outputs.forEach { it.versionCode.set(gitBuild.debugVersionCode(3)) }
}
