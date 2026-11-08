// library.db: Room KMP (JVM + Android) with KSP, confined to this module.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// The exported schema of every database version is committed. No destructive fallback; until 1.0 ships v1 may be
// edited in place (that build needs "clear data"), from then on every version gets a real migration and a test.
room { schemaDirectory("$projectDir/schemas") }

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "dev.coda.store"
        compileSdk = 36
        minSdk = 36
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        getByName("jvmTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.room.testing) // MigrationTestHelper against the exported schemas.
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
}
