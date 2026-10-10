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

        // Live2D Cubism Core 自带的原生库只有 arm64-v8a / x86 / x86_64
        // AAR 里没有 armeabi-v7a，写进去会在打包时找不到 .so
        ndk {
            abiFilters += listOf("arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    // Live2D Cubism Core：libLive2DCubismCoreJNI.so + com.live2d.sdk.cubism.core.*
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
