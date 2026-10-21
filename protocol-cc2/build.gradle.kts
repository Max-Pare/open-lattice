plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.hivemq.mqtt.client)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // Live tests against a real printer run only when these are set, e.g.
    // OPEN_LATTICE_CC2_HOST=192.168.1.116 OPEN_LATTICE_CC2_CODE=xxxxxx ./gradlew :protocol-cc2:test
    environment("OPEN_LATTICE_CC2_HOST", System.getenv("OPEN_LATTICE_CC2_HOST") ?: "")
    environment("OPEN_LATTICE_CC2_CODE", System.getenv("OPEN_LATTICE_CC2_CODE") ?: "")
    environment("OPEN_LATTICE_CC2_CONTROL", System.getenv("OPEN_LATTICE_CC2_CONTROL") ?: "")
}
