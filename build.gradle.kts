// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // crypto-core 用 Kotlin/JVM 插件。必须在此显式声明 apply false：
    // kotlin.android 会把 Kotlin Gradle Plugin 带上类路径但「版本未知」，
    // 子模块再按 alias 解析同 id 插件时无法做兼容性校验 → 报
    // "already on the classpath with an unknown version"（2026-09-21 实测）。
    alias(libs.plugins.kotlin.jvm) apply false
}