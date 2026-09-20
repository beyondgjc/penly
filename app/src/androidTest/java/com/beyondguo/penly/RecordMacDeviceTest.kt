package com.beyondguo.penly

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.penly.crypto.SessionManager
import com.beyondguo.penly.data.UnlockResult
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
 * encrypt-then-MAC 仓库级行为（真机/模拟器）：
 * ① saveEntry 写入四元组 Mac 且可解回；② 磁盘篡改 → 改密被严格拒绝（数据保持原状）；
 * ③ 旧数据（无 Mac）宽容——改密照常成功。破坏性：会 clearAll 覆盖现有库。
 */
@RunWith(AndroidJUnit4::class)
class RecordMacDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<PenlyApp>()
    private val repo = app.repo
    private val store = VaultStore(app)

    private suspend fun seed(): String {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
        return repo.saveEntry(
            id = null, title = "t", category = "",
            account = "acc", secret = "sec", note = "nte",
            totpSecret = "JBSWY3DPEHPK3PXP",
        )
    }

    private suspend fun mutateCurrentRow(id: String, transform: (com.beyondguo.penly.data.VaultItem) -> com.beyondguo.penly.data.VaultItem) {
        val slot = SessionManager.activeSlotOrNull()!!
        val items = store.readItems(slot).toMutableList()
        val i = items.indexOfFirst { it.id == id }
        items[i] = transform(items[i])
        store.writeItems(slot, items)
    }

    /** 当前库 schema：V3=GCM（独立 MAC 恒空，完整性由 tag+AAD 承担）/ V2=CBC+encrypt-then-MAC */
    private suspend fun currentSchema(): Int =
        store.readMeta(SessionManager.activeSlotOrNull()!!)!!.schemaVersion

    @Test
    fun saveEntry_writesFourMacs_andRoundTrips() = runBlocking {
        val id = seed()
        val item = repo.item(id)!!
        // v5.0 起新库为 SCHEMA_V3（GCM）：独立 MAC 四元组恒空；SCHEMA_V2 才写 MAC。
        // 完整性机制的存在性断言按 schema 分派。
        if (currentSchema() == VaultMeta.SCHEMA_V3) {
            assertTrue(item.accountEnc.isNotBlank())
        } else {
            assertTrue(item.accountMac.isNotBlank())
            assertTrue(item.secretMac.isNotBlank())
            assertTrue(item.noteMac.isNotBlank())
            assertTrue(item.totpMac.isNotBlank())
        }
        val e = repo.decryptItem(item)
        assertEquals("acc", e.account)
        assertEquals("sec", e.secret)
        assertEquals("nte", e.note)
        assertEquals("JBSWY3DPEHPK3PXP", e.totp)
    }

    @Test
    fun tamperedCiphertext_changeMasterRejected() = runBlocking {
        val id = seed()
        // 绕过 repo 直接改库，模拟磁盘篡改：翻转 accountEnc 首字符
        mutateCurrentRow(id) { it.copy(accountEnc = "x" + it.accountEnc.drop(1)) }
        val err = repo.changeMasterPassword("master123456", "master654321")
        assertTrue("应报完整性校验失败：$err", err?.contains("完整性") == true)
        // 数据保持原状：原密码仍可解锁（unlock 自 v5.0 起返回 UnlockResult 三态）
        repo.lock()
        assertEquals(UnlockResult.Success, repo.unlock("master123456"))
    }

    @Test
    fun legacyRecordWithoutMac_changeMasterSucceeds() = runBlocking {
        val id = seed()
        mutateCurrentRow(id) { it.copy(accountMac = "", secretMac = "", noteMac = "", totpMac = "") }
        assertNull(repo.changeMasterPassword("master123456", "master654321"))
        // 改密后记录被重加密：V2 路径补上 Mac；V3（GCM）MAC 恒空，断言密文存在
        val item = repo.item(id)
        assertNotNull(item)
        if (currentSchema() == VaultMeta.SCHEMA_V3) {
            assertTrue(item!!.accountEnc.isNotBlank())
        } else {
            assertTrue(item!!.accountMac.isNotBlank())
        }
    }
}
