plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.questtuner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.questtuner"
        minSdk = 32        // Quest 3 出厂 Android 12L (API 32)
        targetSdk = 32     // 保持 32：避免较新 Android 的后台执行限制
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.maybeCreate("debug").apply {
                storeFile = rootProject.file("keystore/debug.keystore")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // P1-2 自更新要求新旧版本签名一致；暂复用 debug keystore。
            // TODO(lead): 正式对外发布前更换专用 release keystore（换签会导致已装用户自更新校验失败，需一次性迁移策略）
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // ADB 协议实现需要 JSON (CNXN payload) 与 BouncyCastle 不引入，使用 JDK 内置 RSA

    testImplementation("junit:junit:4.13.2")
    // org.json 在 Android 单元测试是 stub；SelfUpdater 的 JSON 解析测试需要真实实现
    testImplementation("org.json:json:20240303")
}
