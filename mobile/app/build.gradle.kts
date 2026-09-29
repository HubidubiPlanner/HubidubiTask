plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hubidubios.mobile"
    // Play takes new apps only at its current target level (API 36 from 31 Aug 2026).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hubidubios.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release builds are signed with the PRIVATE key, which never enters the
    // repo: the keystore sits in ~/.keystores and its password in the macOS
    // Keychain. Elsewhere (CI), point HUBIDUBIOS_KEYSTORE and
    // HUBIDUBIOS_KEYSTORE_PASSWORD at them instead.
    signingConfigs {
        create("release") {
            val path = System.getenv("HUBIDUBIOS_KEYSTORE")
                ?: "${System.getProperty("user.home")}/.keystores/hubidubitask-upload.jks"
            val password = providers.environmentVariable("HUBIDUBIOS_KEYSTORE_PASSWORD").orElse(
                providers.exec {
                    commandLine("security", "find-generic-password", "-s", "android-keystore-hubidubitask-upload", "-w")
                    isIgnoreExitValue = true
                }.standardOutput.asText.map { it.trim() })
            if (file(path).exists()) {
                storeFile = file(path)
                storePassword = password.get()
                keyAlias = "private"
                keyPassword = password.get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(project(":core"))

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

    // Background sync.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR scanning for pairing — ZXing core only, no Play Services / ML Kit.
    // CameraX (androidx) supplies the preview + frame stream; ZXing decodes the
    // Y plane of each frame. Nothing here pulls in Google Play Services.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
