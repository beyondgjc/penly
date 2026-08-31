package com.beyondguo.penly.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 未解锁时访问会话密钥抛出 */
class VaultLockedException(message: String = "印迹未解锁") : Exception(message)

/**
 * 会话密钥管理：密钥仅驻内存，锁定即清除。
 * App 切后台由 MainActivity 延迟自动锁定（见 LOCK_DELAY），与小程序「切后台即清除」语义对齐。
 */
object SessionManager {

    @Volatile
    private var key: ByteArray? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun isUnlocked(): Boolean = _unlocked.value

    fun establish(newKey: ByteArray) {
        key = newKey.copyOf()
        _unlocked.value = true
    }

    /** 获取当前会话密钥；未解锁抛 [VaultLockedException] */
    fun requireKey(): ByteArray = key ?: throw VaultLockedException()

    fun lock() {
        key?.fill(0)
        key = null
        _unlocked.value = false
    }
}
