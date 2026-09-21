package com.beyondguo.penly.crypto

import com.beyondguo.penly.data.Slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// VaultLockedException 已随加密内核搬到 crypto-core（同包，见 core 的 KeySession.kt），
// 此处不再重复声明 —— 同包同名会编译冲突。

/**
 * 会话密钥管理：密钥仅驻内存，锁定即清除。
 * App 切后台由 MainActivity 延迟自动锁定（见 LOCK_DELAY），与小程序「切后台即清除」语义对齐。
 *
 * v2 起额外持有**当前生效槽位** [activeSlot]。该值同样只驻内存：
 * 一旦写入 SavedState / DataStore / 备份，"哪个槽位是真库"这一秘密即告泄露，
 * 影子保险库的不可证伪性随之失效（详见《印迹Android_v2技术方案.md》§3.1 边界）。
 *
 * 与 core 的 [KeySession] 的关系：本类是**宿主侧状态机**（密钥 + 槽位），
 * 本批（Phase 0）刻意**不动**它——改状态机属高风险动作，另行一批。
 */
object SessionManager {

    @Volatile
    private var key: ByteArray? = null

    @Volatile
    private var slot: Slot? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun isUnlocked(): Boolean = _unlocked.value

    fun establish(newKey: ByteArray, newSlot: Slot) {
        key = newKey.copyOf()
        slot = newSlot
        _unlocked.value = true
    }

    /** 获取当前会话密钥；未解锁抛 [VaultLockedException] */
    fun requireKey(): ByteArray = key ?: throw VaultLockedException()

    /** 当前生效槽位；未解锁抛 [VaultLockedException]。仅内存，绝不落盘 */
    fun requireSlot(): Slot = slot ?: throw VaultLockedException()

    /** 当前生效槽位，未解锁时为 null。仅内存，绝不落盘 */
    fun activeSlotOrNull(): Slot? = slot

    fun lock() {
        key?.fill(0)
        key = null
        slot = null
        _unlocked.value = false
    }
}
