package com.beyondguo.penly

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.penly.data.Slot
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 影子保险库「不可证伪性」设备级走查（§3.4）。
 *
 * DataStore 以 protobuf 存盘，无法 `cat` 直接读 JSON；本测试用应用自身的
 * [VaultStore] 读出两槽位明文 [VaultMeta]（salt/verify/pwdMode/createdAt/updatedAt
 * 均为明文），断言：
 *  1) 初始化即同时建两槽位（占位槽位非空）
 *  2) 两槽位 createdAt / updatedAt / pwdMode 逐字段相等
 *  3) 设置应急密码前后，各槽位明文 meta 不变（否则 updatedAt>createdAt 即暴露"已设应急"）
 *  4) 改主密码后两槽位 pwdMode / updatedAt 仍对称
 */
@RunWith(AndroidJUnit4::class)
class ShadowVaultDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<PenlyApp>()
    private val repo = app.repo

    private data class MetaSnapshot(
        val createdAt: Long,
        val updatedAt: Long,
        val pwdMode: String,
    )

    private suspend fun snapshot(store: VaultStore): Map<Slot, MetaSnapshot> =
        Slot.values().associate { slot ->
            val m = store.readMeta(slot)!!
            slot to MetaSnapshot(m.createdAt, m.updatedAt, m.pwdMode)
        }

    @Test
    fun bothSlotsCreatedAndIsomorphicAcrossDuressAndMasterChange() = runBlocking {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)

        val store = VaultStore(app)
        assertNotNull("A 槽位必须存在（占位）", store.readMeta(Slot.A))
        assertNotNull("B 槽位必须存在（占位）", store.readMeta(Slot.B))

        val unset = snapshot(store)
        assertEquals("两槽位 createdAt 必须相等", unset[Slot.A]!!.createdAt, unset[Slot.B]!!.createdAt)
        assertEquals("两槽位 updatedAt 必须相等（未设应急前）", unset[Slot.A]!!.updatedAt, unset[Slot.B]!!.updatedAt)
        assertEquals("两槽位 pwdMode 必须相等", unset[Slot.A]!!.pwdMode, unset[Slot.B]!!.pwdMode)

        // 设置应急密码
        assertTrue("初始化后当前应为主槽位", repo.isPrimary())
        val duressErr = repo.setDuressPassword("duress123456")
        assertNull("设置应急密码不应报错: $duressErr", duressErr)

        val set = snapshot(store)
        for (slot in Slot.values()) {
            assertEquals("槽位 $slot createdAt 在设应急前后不变", unset[slot]!!.createdAt, set[slot]!!.createdAt)
            assertEquals("槽位 $slot updatedAt 在设应急前后不变", unset[slot]!!.updatedAt, set[slot]!!.updatedAt)
            assertEquals("槽位 $slot pwdMode 在设应急前后不变", unset[slot]!!.pwdMode, set[slot]!!.pwdMode)
        }

        // 改主密码后两槽位明文仍对称
        val changeErr = repo.changeMasterPassword("master123456", "master654321")
        assertNull("改主密码不应报错: $changeErr", changeErr)
        val after = snapshot(store)
        assertEquals("改密后两槽位 pwdMode 对称", after[Slot.A]!!.pwdMode, after[Slot.B]!!.pwdMode)
        assertEquals("改密后两槽位 updatedAt 对称", after[Slot.A]!!.updatedAt, after[Slot.B]!!.updatedAt)
    }
}
