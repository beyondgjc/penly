plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.beyondguo.penly"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.beyondguo.penly"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // TODO 正式上线前替换为正式签名；当前用 debug 签名便于真机验证
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
