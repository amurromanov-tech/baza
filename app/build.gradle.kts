plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.family.base"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.base"
        minSdk = 24
        targetSdk = 34
        versionCode = 287
        versionName = "23.3.18"

        manifestPlaceholders["appAuthRedirectScheme"] = "com.family.base"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/baza-upload-key.jks"
            val keystoreFile = file(keystorePath)

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
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
        viewBinding = true
        buildConfig = true
    }

    // ============================================================
    // 🔧 ФИКС КРАША FFmpegKit (v23.3.18):
    // Указываем ТОЧНЫЕ пути для каждой архитектуры, а не wildcard.
    // Это гарантирует, что Gradle возьмёт libc++_shared.so,
    // совместимый с FFmpegKit, а не "чужой" (например, от другой библиотеки).
    // ============================================================
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
                // Остальные библиотеки FFmpegKit — тоже явно:
                "lib/arm64-v8a/libavcodec.so",
                "lib/armeabi-v7a/libavcodec.so",
                "lib/x86/libavcodec.so",
                "lib/x86_64/libavcodec.so",
                "lib/arm64-v8a/libavformat.so",
                "lib/armeabi-v7a/libavformat.so",
                "lib/x86/libavformat.so",
                "lib/x86_64/libavformat.so",
                "lib/arm64-v8a/libavutil.so",
                "lib/armeabi-v7a/libavutil.so",
                "lib/x86/libavutil.so",
                "lib/x86_64/libavutil.so",
                "lib/arm64-v8a/libswscale.so",
                "lib/armeabi-v7a/libswscale.so",
                "lib/x86/libswscale.so",
                "lib/x86_64/libswscale.so"
            )
        }
    }
}

dependencies {
    // ===== CORE =====
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // ===== UI =====
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // ===== SECURITY =====
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ===== ROOM =====
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // ===== COROUTINES =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ===== WORKMANAGER =====
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ===== IMAGES =====
    implementation("io.coil-kt:coil:2.4.0")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    // ===== NETWORK =====
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // ===== OAUTH =====
    implementation("net.openid:appauth:0.11.1")

    // ===== CAMERA =====
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // ===== ML KIT =====
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // ===== ВИДЕО =====
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // ===== FFmpegKit =====
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")

    // ===== TEST =====
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
