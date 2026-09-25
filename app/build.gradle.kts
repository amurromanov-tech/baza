plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.family.base"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.base"
        minSdk = 24
        targetSdk = 34
        versionCode = 21   // увеличивать при каждом обновлении
        versionName = "4.1.0"   // версия для пользователя

        manifestPlaceholders["appAuthRedirectScheme"] = "com.family.base"
    }

    // ===== ИСПОЛЬЗУЕМ СУЩЕСТВУЮЩУЮ КОНФИГУРАЦИЮ 'debug' =====
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        dataBinding = false
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coil для загрузки изображений
    implementation("io.coil-kt:coil:2.4.0")

    // PhotoView для полноэкранного просмотра фото с pinch-to-zoom
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    // AppAuth для OAuth
    implementation("net.openid:appauth:0.11.1")

    // Retrofit для API
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit для сканирования штрих-кодов
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
   // ... остальные зависимости
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
}
