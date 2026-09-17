package com.beyondguo.penly

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.Slot
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultStore
import com.beyondguo.penly.data.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.FileObserver

/**
 * P0 锚点测试：commitSlots 必须是「一次事务 = 一次文件替换」。
 *
 * 防的是将来有人把 commitSlots 内部拆回多次 writeMeta/writeItems（各自一次 edit）——
 * 那会重新打开 P0 半状态窗口（changeMasterPassword 唯一密码永久失效、
 * setDuressPassword / initVault 断链自锁），而链路不变量 I 破坏与「合法影子槽位」
 * 在磁盘上不可区分、无检测自愈通道——只能靠这个文件级观测锚住。
 *
 * 观测原理：Preferences DataStore 的每次 edit = 写临时文件 + okio atomicMove
 * （rename 到正式名）→ 监控 datastore 目录的 MOVED_TO 事件、path 过滤到
 * penly_vault.preferences_pb，事件次数 = 事务提交次数。
 *
 * 注意：本测试会 clearAll 覆盖设备上的现有库（与其他 device test 同款破坏性）。
 */
@RunWith(AndroidJUnit4::class)
class SlotWriteAtomicityDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<PenlyApp>()
    private val store = VaultStore(app)
    private val dataDir = File(app.filesDir, "datastore").apply { mkdirs() }

    /** MOVED_TO 计数器：只统计 penly_vault.preferences_pb 的原子替换（tmp 文件事件被 path 过滤） */
    private class ReplacementCounter(dataDir: File) : FileObserver(dataDir, MOVED_TO) {
        private var count = 0

        override fun onEvent(event: Int, path: String?) {
            if (path == "penly_vault.preferences_pb") {
                synchronized(this) { count++ }
            }
        }

        fun observed(): Int = synchronized(this) { count }
    }

    private fun metaFor(pwd: String): VaultMeta {
        val salt = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(pwd, salt)
        val (v, vi) = CryptoEngine.makeVerify(key)
        return VaultMeta(
            saltB64 = salt, verifyB64 = v, verifyIvB64 = vi,
            pwdMode = VaultMeta.MODE_CUSTOM, createdAt = 1L, updatedAt = 1L,
        )
    }

    /** 基线清场 → 等 clearAll 自己的替换事件落地 → 从零开始计数的监听器 */
    private suspend fun freshCounter(): ReplacementCounter {
        store.clearAll()
        val counter = ReplacementCounter(dataDir)
        Thread.sleep(500) // 等 clearAll 的 MOVED_TO 送达（早于 startWatching，不计数）
        counter.startWatching()
        return counter
    }

    @Test
    fun commitSlots_replacesFileExactlyOnce() = runBlocking {
        val counter = freshCounter()
        try {
            store.commitSlots {
                write(Slot.A, meta = metaFor("pwd666666"), items = listOf(VaultItem(id = "a_1", title = "t1")))
                write(Slot.B, meta = metaFor("duress999999"), items = listOf(VaultItem(id = "b_1", title = "t2")))
            }
            Thread.sleep(800) // 等内核事件送达 observer 线程
        } finally {
            counter.stopWatching()
        }
        assertEquals(
            "commitSlots 单事务必须只产生一次文件替换（>1 = 事务被拆成多次写）",
            1,
            counter.observed(),
        )
    }

    /** 对照锚点：旧的逐 key 写法（两次 edit）= 两次替换——校准计数器，也是拆分行为的画像 */
    @Test
    fun legacySplitWrites_replaceFileTwice() = runBlocking {
        val counter = freshCounter()
        try {
            store.writeMeta(Slot.A, metaFor("pwd666666"))
            store.writeMeta(Slot.B, metaFor("duress999999"))
            Thread.sleep(800)
        } finally {
            counter.stopWatching()
        }
        assertEquals(2, counter.observed())
    }

    @Test
    fun commitSlots_landsAllFourKeysTogether() = runBlocking {
        store.clearAll()
        val metaA = metaFor("pwd666666")
        val metaB = metaFor("duress999999")
        store.commitSlots {
            write(Slot.A, meta = metaA, items = listOf(VaultItem(id = "a_1", title = "t1")))
            write(Slot.B, meta = metaB, items = listOf(VaultItem(id = "b_1", title = "t2")))
        }
        // 「全」：四 key 同时落地且值正确
        assertEquals(metaA.saltB64, store.readMeta(Slot.A)!!.saltB64)
        assertEquals(1, store.readItems(Slot.A).size)
        assertEquals(metaB.saltB64, store.readMeta(Slot.B)!!.saltB64)
        assertEquals(1, store.readItems(Slot.B).size)
        // 未声明 dropLegacy 时 legacy 不受影响
        assertNull(store.readLegacy())
    }

    @Test
    fun resetAll_withWrites_clearsOldWorldInSameTransaction() = runBlocking {
        store.clearAll()
        store.writeMeta(Slot.A, metaFor("old-world")) // 旧世界预置
        val metaB = metaFor("new-world")
        store.commitSlots {
            resetAll()
            write(Slot.B, meta = metaB, items = listOf(VaultItem(id = "b_1", title = "t")))
        }
        // 旧世界清掉、新世界落地——且发生在同一次事务（上面 MOVED_TO 用例已锚定单次替换）
        assertNull(store.readMeta(Slot.A))          // meta key 被移除 → readMeta 返回 null
        assertTrue(store.readItems(Slot.A).isEmpty()) // items key 被移除 → readItems 返回空列表（:61 缺失 key 语义）
        assertEquals(metaB.saltB64, store.readMeta(Slot.B)!!.saltB64)
        assertEquals(1, store.readItems(Slot.B).size)
    }
}
