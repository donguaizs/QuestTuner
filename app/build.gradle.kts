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
        // 发布恒等式（BUG-043 教训：tag 与内嵌版本脱节 → 自更新同版本循环）：
        //   git tag 名 == "v" + versionName；打 tag 前必须先改本处两个值。
        //   例：versionName 0.2.2 → tag v0.2.2 → Release APK 内嵌 0.2.2(3)。
        //   SelfUpdater 防降级比较用 versionName（SelfUpdater.kt versionGreaterThan）。
        versionCode = 3
        versionName = "0.2.2"
        // BUG-033（lead 决策）：中文单语产品 + 依赖库资源裁剪（保留 zh 与库默认 en，剥离其余 locale）
        resourceConfigurations.addAll(listOf("zh", "en"))
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
    testOptions {
        unitTests {
            // Robolectric 访问资源必需（round-8 测试质量专项）
            isIncludeAndroidResources = true
            // 构建机文件沙箱拒绝测试 JVM 写用户主目录（Robolectric 下载锁 ~/.robolectric-download-lock
            // 与 .m2 缓存）——user.home 重定向到工作区可写目录；android-all jar 由 Gradle
            // 依赖解析预取后放入该 m2 仓库（Robolectric 走 maven.repo.local 查找）
            all {
                it.systemProperty("user.home", "F:\\workspace\\toolchain\\robolectric-home")
                it.systemProperty("maven.repo.local",
                    "F:\\workspace\\toolchain\\robolectric-home\\.m2\\repository")
            }
        }
    }
    lint {
        //不上架 Google Play（GitHub Releases 侧载分发）；targetSdk 32 是刻意决策
        //（规避较新 Android 后台执行限制，见 defaultConfig 注释），此 Play 专项检查不适用
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // ADB 协议实现需要 JSON (CNXN payload) 与 BouncyCastle 不引入，使用 JDK 内置 RSA
    // BUG-038：已删 lifecycle-runtime-ktx（lifecycleScope/repeatOnLifecycle 零使用）与
    // datastore-preferences（零使用挂起 5 轮）——viewModelScope 由 lifecycle-viewmodel-compose 传递提供

    testImplementation("junit:junit:4.13.2")
    // org.json 在 Android 单元测试是 stub；SelfUpdater 的 JSON 解析测试需要真实实现
    testImplementation("org.json:json:20240303")

    // ---- Round8 测试质量专项 ----
    testImplementation("org.robolectric:robolectric:4.12.2")     // 盲区3 框架交互测试
    // 注意：不要把 android-all-instrumented 加进 testImplementation——它内置
    // RobolectricInternals 注入的框架类，会遮蔽 mockable android.jar，令普通 JUnit
    // 测试在非沙箱环境初始化框架类时 NPE（round-8 教训）。预取方式：gradle 依赖解析
    // 一次后把 jar 复制进 robolectric-home/.m2（Robolectric 运行时经 maven.repo.local 离线命中）。
    debugImplementation("androidx.compose.ui:ui-test-manifest")  // Compose 测试需要（BOM 管版本）
    testImplementation("androidx.compose.ui:ui-test-junit4")     // 盲区1 Compose UI 测试（BOM 管版本）
}
