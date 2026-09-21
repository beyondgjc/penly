package com.beyondguo.penly.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 未解锁时访问会话密钥抛出 */
class VaultLockedException(message: String = "印迹未解锁") : Exception(message)

/**
 * 会话密钥管理（SDK 版）：密钥仅驻内存，锁定即清除。
 *
 * 与宿主 `SessionManager` 的关系——**这里刻意只有"密钥"，没有"槽位"**：
 * 当前生效槽位（影子保险库的 A/B）是 penly 的产品语义，不是加密能力，
 * 由宿主自己保管（见工作区《印迹加密SDK_接口清单.md》§「明确不暴露」）。
 *
 * 宿主仍需保证：该值一旦写入 SavedState / DataStore / 备份，
 * "哪个槽位是真库"这一秘密即告泄露，影子保险库的不可证伪性随之失效。
 *
 * 自动锁定由宿主负责触发 [lock]（penly 走 `PenlyApp` 的 started 计数）。
 */
object KeySession {

    @Volatile
    private var key: ByteArray? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun isUnlocked(): Boolean = _unlocked.value

    /** 建立会话：**复制**入内存，不持有调用方数组的引用（调用方擦除自己的副本不影响会话） */
    fun establish(newKey: ByteArray) {
        key?.fill(0)
        key = newKey.copyOf()
        _unlocked.value = true
    }

    /** 获取当前会话密钥；未解锁抛 [VaultLockedException] */
    fun requireKey(): ByteArray = key ?: throw VaultLockedException()

    /** 锁定：先擦除密钥字节再释放引用（避免明文密钥残留在堆上） */
    fun lock() {
        key?.fill(0)
        key = null
        _unlocked.value = false
    }
}
