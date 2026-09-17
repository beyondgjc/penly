package com.beyondguo.penly

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.penly.crypto.MacVerificationException
import com.beyondguo.penly.crypto.SessionManager
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 坏数据实测夹具（用户手动验证详情页用）：
 *
 * 在设备上构造「1 条正常 + 1 条 accountEnc 被篡改」的金库后**保留现场**，
 * 供手动验证：解锁（master123456）→ 点开「坏条目-详情页实测」→ 详情页应显示
 * 中性提示「记录读取失败，请重试」而非空白/崩溃；点开正常条目功能照常；
 * 列表内坏条目副标题为空但不崩（ListScreen 逐条降级）。
 *
 * 自证部分：直接断言 decryptItem 对坏条目抛 [MacVerificationException]、
 * 好条目解密正常——即 DetailScreen catch (_: Exception) 所覆盖的异常类型。
 * 破坏性：会 resetVault 清库。
 */
@RunWith(AndroidJUnit4::class)
class BadDataFixtureDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<PenlyApp>()
    private val repo = app.repo
    private val store = VaultStore(app)

    @Test
    fun leaveBadRowForManualDetailCheck() = runBlocking {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
        val goodId = repo.saveEntry(
            id = null, title = "正常条目", category = "",
            account = "ok@example.com", secret = "good-pwd", note = "",
        )
        val badId = repo.saveEntry(
            id = null, title = "坏条目-详情页实测", category = "",
            account = "bad@example.com", secret = "bad-pwd", note = "这条的密文被篡改",
        )

        // 绕过 repo 直接改库：翻转坏条目 accountEnc 首字符（模拟磁盘篡改/位翻转）
        val slot = SessionManager.activeSlotOrNull()!!
        val items = store.readItems(slot).toMutableList()
        val i = items.indexOfFirst { it.id == badId }
        items[i] = items[i].copy(accountEnc = "x" + items[i].accountEnc.drop(1))
        store.writeItems(slot, items)

        // 自证 1：坏条目读取 → MacVerificationException（DetailScreen catch (_: Exception) 所覆盖）
        val bad = repo.item(badId)!!
        try {
            repo.decryptItem(bad!!)
            fail("坏条目应当抛 MacVerificationException")
        } catch (e: MacVerificationException) {
            assertTrue(e.message?.contains("完整性") == true)
        }
        // decryptAccount 同样抛（列表副标题路径的异常源）
        try {
            repo.decryptAccount(bad)
            fail("decryptAccount 应当抛 MacVerificationException")
        } catch (_: MacVerificationException) {
        }

        // 自证 2：好条目完全正常
        val good = repo.item(goodId)!!
        assertEquals("ok@example.com", repo.decryptAccount(good))
        assertEquals("good-pwd", repo.decryptItem(good).secret)
    }
}
