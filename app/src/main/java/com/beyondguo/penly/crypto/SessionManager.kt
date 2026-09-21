package com.beyondguo.penly.crypto

import com.beyondguo.penly.data.Slot
import kotlinx.coroutines.flow.StateFlow

// VaultLockedException 已随加密内核搬到 crypto-core（同包，见 core 的 KeySession.kt），
// 此处不再重复声明 —— 同包同名会编译冲突。

/**
 * 会话状态机（宿主侧）：**密钥** + **当前生效槽位**。
 *
 * ## 从 Phase 1 起的职责划分
 * 密钥本身已交给 SDK 的 [KeySession]（纯密钥、无产品语义），本类只做两件事：
 * 1. **转发密钥操作** 到 [KeySession] —— 密钥的实际存储、擦除、解锁状态全在那边，
 *    本类不再自己持有 `ByteArray`，从根上消除"两份密钥状态不一致"的可能；
 * 2. **保管当前槽位** [slot] —— 影子保险库的 A/B 是 penly 的产品语义，不是加密能力，
 *    因此**不进 SDK**（见工作区《印迹加密SDK_接口清单.md》§「明确不暴露」）。
 *
 * 对外 API 与本类改造前**完全一致**，故 26 个调用方（含 4 个 deviceTest）一行未改。
 *
 * ## 槽位的保密要求（未变）
 * [slot] 只驻内存：一旦写入 SavedState / DataStore / 备份，"哪个槽位是真库"
 * 这一秘密即告泄露，影子保险库的不可证伪性随之失效
 * （详见《印迹Android_v2技术方案.md》§3.1 边界）。
 *
 * ## 锁定语义
 * App 切后台由 [PenlyApp] 的生命周期回调延迟触发，与小程序「切后台即清除」语义对齐。
 * 注意 [lock] 必须**同时**清掉两处状态（KeySession 的密钥 + 本类的槽位）——
 * 只清一处会留下"密钥没了但槽位还在"或反过来的中间态。
 */
object SessionManager {

    @Volatile
    private var slot: Slot? = null

    /** 解锁状态直接来自 SDK 会话：单一事实来源，不存在两份状态漂移 */
    val unlocked: StateFlow<Boolean> get() = KeySession.unlocked

    fun isUnlocked(): Boolean = KeySession.isUnlocked()

    /**
     * 建立会话。
     *
     * 顺序说明：**先设槽位再建立密钥会话**——`KeySession.establish` 会把
     * [KeySession.unlocked] 置为 true，UI 观察者可能立刻开始读库并调用
     * [activeSlotOrNull]/[requireSlot]，此时槽位必须已经就绪。
     */
    fun establish(newKey: ByteArray, newSlot: Slot) {
        slot = newSlot
        KeySession.establish(newKey)
    }

    /** 获取当前会话密钥；未解锁抛 [VaultLockedException] */
    fun requireKey(): ByteArray = KeySession.requireKey()

    /** 当前生效槽位；未解锁抛 [VaultLockedException]。仅内存，绝不落盘 */
    fun requireSlot(): Slot = slot ?: throw VaultLockedException()

    /** 当前生效槽位，未解锁时为 null。仅内存，绝不落盘 */
    fun activeSlotOrNull(): Slot? = slot

    /** 锁定：密钥擦除（委托 [KeySession]）+ 槽位清除，两处同时归零 */
    fun lock() {
        slot = null
        KeySession.lock()
    }
}
