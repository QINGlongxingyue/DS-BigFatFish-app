plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.dsaiapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.dsaiapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只带 64 位：真机是 arm64-v8a，模拟器是 x86_64。
        // 32 位的 x86 库（onnxruntime + sherpa 约 36MB）没有任何目标设备需要，砍掉。
        // 注意 Live2D Cubism Core 的 AAR 本身也不含 armeabi-v7a。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // 用 debug 签名给 release 包签名：这样 R8 压缩过的包也能直接装到手机
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }

    // Cubism SDK for Java 源码编译目标是 Java 7；这里统一按 17 产出字节码。
    //
    // ⚠️ 刻意不用 `kotlin { jvmToolchain(17) }`：那会让 Gradle 通过 foojay 去
    // github.com 下载 JDK 17，而本机 github.com 不可达，构建会直接失败。
    // 改成"用当前 JDK 编译、产出 17 字节码"，Kotlin 侧靠下面的
    // kotlinOptions.jvmTarget 对齐，效果一样但不联网。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // debug 版需要 BuildConfig.DEBUG 来决定要不要显示排查工具条
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // ASR 的 onnx 模型不能压缩：不压缩才能内存映射直接读，
        // 否则每次初始化都要把它整个解压进内存
        noCompress += listOf("onnx")
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // 核心图标集（Settings / Phone / Send），版本由 Compose BOM 统一管理
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ⚠️ 刻意不引 androidx.compose.material.icons.extended：那套库含 5000+ 图标 × 5 种风格，
    // 不开 R8 的 debug 包会因此多出几十 MB 的 dex。用到的图标走 material-icons-core
    // （Settings / Phone / Send 都在核心集里），切换图标用本地矢量 ic_swap_horiz.xml。

    // Live2D Cubism Core：libLive2DCubismCoreJNI.so + com.live2d.sdk.cubism.core.*
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // 豆包（火山引擎）双向流式 TTS 走 WebSocket，Android 没有内置 WS 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
