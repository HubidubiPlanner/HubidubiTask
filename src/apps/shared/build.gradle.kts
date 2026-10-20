plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The agent client speaks HTTP; OkHttp runs the same on Android and the JVM.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Claude is spoken to through Anthropic's own SDK, not the OpenAI shape.
    implementation("com.anthropic:anthropic-java:2.34.0")
    // Pairing and sync crypto: J-PAKE (the typed code as a PAKE password) and
    // ML-KEM-768 (post-quantum), lightweight API only, never as a JCA provider.
    api("org.bouncycastle:bcprov-jdk18on:1.86")
    // Android ships org.json in the platform; the desktop app adds it at runtime.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    // SafeFetch is tested against a local HTTPS server with a certificate made on the spot.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // The logic moved here from the Android app keeps its JUnit 4 tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
