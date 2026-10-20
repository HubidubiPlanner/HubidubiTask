import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jmdns:jmdns:3.5.12")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    // An agent at home over HTTPS with a self-signed certificate, for pinning.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

// The runtime bundled with the app: JDK 27 or newer, whose TLS offers the
// post-quantum hybrid key exchange X25519MLKEM768 first by default (JEP 527),
// for the sync server and for every connection the app makes. Gradle itself
// and the compiler stay on JDK 17.
val runtimeJdk: String = System.getenv("HUBIDUBITASK_RUNTIME_JDK")
    ?: "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"

val checkRuntimeJdk by tasks.registering {
    doLast {
        val version = file("$runtimeJdk/release").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("JAVA_VERSION=") }?.substringAfter('"')?.substringBefore('"')
        val major = version?.substringBefore('.')?.toIntOrNull()
        check(major != null && major >= 27) {
            "The app bundles JDK 27 or newer (post-quantum TLS); $runtimeJdk is ${version ?: "missing"}. " +
                "Install it (brew install openjdk) or point HUBIDUBITASK_RUNTIME_JDK at one."
        }
    }
}

tasks.matching { it.name.startsWith("package") || it.name.startsWith("create") && it.name.contains("Distributable") ||
    it.name.startsWith("run") && it.name != "run" }.configureEach { dependsOn(checkRuntimeJdk) }

compose.desktop {
    application {
        mainClass = "com.hubidubitask.desktop.MainKt"
        javaHome = runtimeJdk
        // The sync server's time limits per request and answer (seconds), read
        // once when the JDK's HTTP server loads: a slow client can't hold a thread.
        jvmArgs += listOf("-Dsun.net.httpserver.maxReqTime=30", "-Dsun.net.httpserver.maxRspTime=120")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "HubidubiTask"
            packageVersion = project.property("hubidubitask.version") as String
            description = "HubidubiTask: tasks, notes and feeds, synced with your phone"
            // The embedded HTTPS server, SQLite (JDBC) and the TLS/crypto the
            // peer protocol needs, kept in the bundled runtime.
            modules("jdk.httpserver", "java.sql", "java.naming", "jdk.crypto.ec", "java.management")
            linux {
                iconFile.set(project.file("icon.png"))
                packageName = "hubidubitask"
                appCategory = "Office"
                menuGroup = "Office"
                shortcut = true
            }
            macOS {
                bundleID = "com.hubidubitask.desktop"
                iconFile.set(project.file("icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>HubidubiTask syncs with your phone over your local network.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>HubidubiTask records voice memos you capture.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_hubidubios-peer._tcp</string></array>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.test { useJUnitPlatform() }
