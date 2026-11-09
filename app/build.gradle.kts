import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.cnumed.mlms"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cnumed.mlms"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 릴리즈 서명 정보는 git에 안 올라가는 루트의 keystore.properties에서 읽는다.
    // 파일이 없으면 debug 키로 서명한다.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val releaseSigning = if (keystoreProps.isEmpty) null else signingConfigs.create("release") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
    }

    buildTypes {
        release {
            // R8 활성화 — 코드 축소·난독화와 함께 proguard-rules.pro의
            // -assumenosideeffects 규칙으로 android.util.Log 호출을 제거한다.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    // full: PPT → PDF 변환(글꼴·OCR 포함) / lite: 변환 기능 없이 가벼운 버전 — 같은 앱(패키지)이라 서로 덮어 설치된다
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("lite") { dimension = "edition"; versionNameSuffix = "-lite" }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // BuildConfig.DEBUG로 디버그 전용 로깅을 가른다
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Material
    implementation("com.google.android.material:material:1.11.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // ViewModel + LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Fragment
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Jsoup
    implementation("org.jsoup:jsoup:1.17.2")

    // Room
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")

    // WorkManager + Hilt integration
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // PPTX → PDF 변환 (Apache PDFBox 안드로이드 포팅 — PDF 작성·글꼴 서브셋 임베딩)
    "fullImplementation"("com.tom-roush:pdfbox-android:2.0.27.0")
    // 그림 속 글자 인식(한글+영문) — 모델을 앱에 내장(오프라인, Play 서비스 다운로드 대기 없음)
    "fullImplementation"("com.google.mlkit:text-recognition-korean:16.0.1")

    // org.json is part of the Android SDK — no explicit dependency needed
}
