plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 加密内核（crypto-core）—— 纯 JVM，**不允许出现任何 Android 依赖**。
 *
 * 这条约束不是洁癖：它的存在意义就是「脱离 Android 也能跑」，
 * 于是固定向量测试可以在 JVM 上秒级执行（`:crypto-core:test`），
 * 不需要连真机/模拟器。TEE / Keystore 那类平台能力由宿主 App 实现
 * [com.beyondguo.penly.crypto.KeyWrapper] 接缝注入。
 *
 * 若将来有人往这里加 `android.*` import，请先想清楚是否真的不该留在宿主。
 */
kotlin {
    // 与 app 的 jvmTarget 11 对齐：同一个密码学实现，不允许两端字节码目标不一致
    jvmToolchain(11)
}

dependencies {
    // HKDF / GCM 的子密钥隔离用；Argon2id 用 BouncyCastle 轻量 API
    // （不经 JCA provider，与 app 侧同款用法，避开与系统内置旧版 BC 的冲突）
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    // KeySession.unlocked 是 StateFlow（API 面的一部分，故为 api 而非 implementation）
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // 固定向量测试里有 Argon2id 64MiB 档（KEK 强度验证），需要稍多堆内存
    maxHeapSize = "1g"
}
