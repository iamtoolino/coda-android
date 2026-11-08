plugins { id("com.android.library") version "9.4.1" }

val work = rootDir.resolve("../../build/ffmpeg-ext")
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 36
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api("androidx.media3:media3-decoder:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
}

androidComponents.onVariants { variant ->
    variant.sources.java?.addStaticSourceDirectory(work.resolve("media-1.11.1/libraries/decoder_ffmpeg/src/main/java").path)
    variant.sources.jniLibs?.addStaticSourceDirectory(work.resolve("jniLibs").path)
    variant.sources.assets?.addStaticSourceDirectory(rootDir.resolve("assets").path)
}
