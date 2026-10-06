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
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "com.hubidubios.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "HubidubiOS"
            packageVersion = "1.5.0"
            description = "HubidubiOS for macOS"
            // The embedded HTTPS server, SQLite (JDBC) and the TLS/crypto the
            // peer protocol needs, kept in the bundled runtime.
            modules("jdk.httpserver", "java.sql", "java.naming", "jdk.crypto.ec", "java.management")
            macOS {
                bundleID = "com.hubidubios.desktop"
                iconFile.set(project.file("icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>HubidubiOS syncs with your phone over your local network.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>HubidubiOS records voice memos you capture.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_hubidubios-peer._tcp</string></array>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.test { useJUnitPlatform() }
