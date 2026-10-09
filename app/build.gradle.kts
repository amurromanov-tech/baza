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
        versionCode = 110
        versionName = "14.1.0"

        manifestPlaceholders["appAuthRedirectScheme"] = "com.family.base"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ============================================================
    // 🆕 v14.1.0: RELEASE-ПОДПИСЬ (постоянный keystore)
    // Читает секреты из переменных окружения (GitHub Secrets).
    // Если секретов нет (локальная сборка) — release подписывается debug-ключом.
    // ============================================================
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

            // 🆕 v14.1.0: подключаем release-подпись, если keystore доступен.
            // Иначе — fallback на debug (для локальной сборки без секретов).
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
    kapt("androidx.room:room-compiler:2.6.1")           // ← kapt вместо annotationProcessor

    // ===== COROUTINES =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ===== WORKMANAGER (напоминания) =====
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

    // ===== ML KIT (BARCODE) =====
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // ===== OCR =====
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

    // ===== TEST =====
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
