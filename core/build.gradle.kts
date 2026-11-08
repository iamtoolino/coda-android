plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.okhttp)
    implementation(project(":store")) // Resolves :store's JVM variant.
    implementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.mockwebserver)
    testFixturesImplementation(libs.okhttp.tls) // An https endpoint with an untrusted certificate.
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(project(":store")) // The cache index of test caches (TestCacheIndex).
    testFixturesImplementation(libs.androidx.sqlite.bundled)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303") // Read the Swift golden manifest in JVM tests.
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
