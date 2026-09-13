import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 正式签名：android/keystore.properties（已 gitignore，本机维护）指向仓库外的 penly-release.jks；
// 文件缺失（CI / 他机首次拉取）时自动回退 debug 签名，构建永不因签名材料中断。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.beyondguo.penly"
    // 34→36（2026-09-13）：解锁 fragment-ktx ≥1.8（原钉 1.7.1，见 libs.versions.toml 注释）。
    // 仅升 compileSdk 不升 targetSdk（34）——运行时行为不变（edge-to-edge 强制等
    // 只由 targetSdk 决定），无迁移风险。
    compileSdk = 36

    // 设备测试默认针对 debug 构建（开发态）；release 混淆回归时用
    // -PtestBuildType=release 切换，让 22 例设备测试直接跑在 R8 包上
    testBuildType = (project.findProperty("testBuildType") as String?) ?: "debug"

    defaultConfig {
        applicationId = "com.beyondguo.penly"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // 正式签名：keystore.properties 存在时用 release keystore；缺失时回退 debug 签名便于验证
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 测试 APK（androidTest）被 R8 处理时的专用规则通道：测试包的 R8
            // 不读 proguard-rules.pro，缺类抑制规则必须挂这里（proguard-test-rules.pro）
            testProguardFiles("proguard-test-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // 显式升级 fragment ≥1.3.0：biometric 1.1.0 会把 fragment 钉在 1.2.5，
    // 其 FragmentActivity 对 requestCode 强校验"仅低 16 位"，与 Activity Result API
    // 默认 registry 的随机 requestCode 冲突，launch 文件选择器时必现 crash。
    // fragment 1.3.0+ 已移除该校验（1.7.1 兼容 compileSdk 34）。
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)
    // 汉字 → 拼音首字母：列表 A–Z 分组与右侧索引条需要（纯 Java，无传递依赖，
    // 在 Maven Central 上；tinypinyin 只发 JitPack 且坐标不可用，故选 pinyin4j）
    implementation(libs.pinyin4j)
    // 扫码录入 2FA 密钥（v3.0 项目④）：自带取景 Activity 与相机权限流程
    implementation(libs.zxing.android.embedded)
    // 端内 AI 检索：ONNX Runtime（Android CPU 推理）+ bge-small-zh-v1.5 int8 模型（assets/models/）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation(libs.junit)
    // JVM 单测跑中文召回评测用桌面版 onnxruntime（与 Android 版同一套 ai.onnxruntime API）
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.ui.tooling)
}

// 单测运行时排除 Android 版 ORT：其 AAR 只有移动端 native 库，
// 桌面 JVM 加载会失败，且与桌面版 jar 的 ai.onnxruntime 类重复
configurations.matching { it.name == "testRuntimeClasspath" }.all {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
