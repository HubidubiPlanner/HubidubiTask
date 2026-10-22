plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    // The Claude SDK's dependencies each carry the same licence metadata;
    // none of it is read at runtime.
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/INDEX.LIST")
    }
    namespace = "com.hubidubitask.mobile"
    // Play takes new apps only at its current target level (API 36 from 31 Aug 2026).
    compileSdk = 36

    defaultConfig {
        // Play knows the app by this id (since 1.0.0). -Phubidubitask.appId=…
        // installs a build under another id, which is a second, empty app
        // beside the one from Play.
        applicationId = (project.findProperty("hubidubitask.appId") as String?)
            ?: "com.saferendering.hubidubitask"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.property("hubidubitask.versionCode") as String).toInt()
        versionName = project.property("hubidubitask.version") as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A release build is signed only where a key is set up, and never from the
    // repo: HUBIDUBITASK_KEYSTORE (with _PASSWORD and _ALIAS) in the
    // environment, or hubidubitask.keystore, .keystoreAlias and
    // .keystoreKeychain in ~/.gradle/gradle.properties, the password then read
    // from that macOS Keychain item. Without one the release build comes out
    // unsigned, the way F-Droid builds it to compare with the signed APK of
    // the GitHub release (deploy/f-droid/README.md).
    signingConfigs {
        create("release") {
            val path = System.getenv("HUBIDUBITASK_KEYSTORE")
                ?: project.findProperty("hubidubitask.keystore") as String?
            if (path != null && file(path).exists()) {
                val keychain = project.findProperty("hubidubitask.keystoreKeychain") as String?
                val password = System.getenv("HUBIDUBITASK_KEYSTORE_PASSWORD")
                    ?: keychain?.let { item ->
                        providers.exec {
                            commandLine("security", "find-generic-password", "-s", item, "-w")
                            isIgnoreExitValue = true
                        }.standardOutput.asText.get().trim()
                    }
                    ?: error("No password for $path: set HUBIDUBITASK_KEYSTORE_PASSWORD or hubidubitask.keystoreKeychain")
                storeFile = file(path)
                storePassword = password
                keyAlias = System.getenv("HUBIDUBITASK_KEYSTORE_ALIAS")
                    ?: project.findProperty("hubidubitask.keystoreAlias") as String? ?: "upload"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let { signingConfig = it }
            // R8 drops unused code and shortens names: a smaller app that
            // starts faster. What is only reached by name is kept in
            // proguard-rules.pro; the libraries bring their own rules too.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Google's encrypted list of the libraries inside stays out of the APK,
    // which F-Droid builds and reads; Play still gets it in the bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Jetpack Compose — the native UI (no WebView).
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Local mirror: Room, encrypted with SQLCipher.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Transport with certificate pinning.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // TLS with post-quantum key exchange (X25519MLKEM768 by default), see HubidubiTaskApplication.
    implementation("org.conscrypt:conscrypt-android:2.7.0")

    // Background sync.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR scanning for pairing — ZXing core only, no Play Services / ML Kit.
    // CameraX (androidx) supplies the preview + frame stream; ZXing decodes the
    // Y plane of each frame. Nothing here pulls in Google Play Services.

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
