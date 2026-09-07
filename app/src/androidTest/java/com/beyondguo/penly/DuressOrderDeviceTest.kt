package com.beyondguo.penly

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.SessionManager
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复现「设置主密码后再设置应急密码 → 报错『当前会话不支持此操作』」。
 *
 * 与 [ShadowVaultDeviceTest] 的区别：那个用例的顺序是
 * initVault → setDuress → changeMaster，而用户实际顺序是
 * initVault → changeMaster(设置主密码) → setDuress。
 * 两条路径下 aux 凭证的状态不同，故单独覆盖。
 */
@RunWith(AndroidJUnit4::class)
class DuressOrderDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<PenlyApp>()
    private val repo = app.repo

    /** 路径 A：custom 初始化 → 改主密码 → 设应急密码 */
    @Test
    fun changeMasterBeforeSetDuress() = runBlocking {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
        val p0 = repo.isPrimary()
        val e1 = repo.changeMasterPassword("master123456", "master654321")
        val p1 = repo.isPrimary()
        val e2 = repo.setDuressPassword("duress123456")
        assertNull(
            "路径A: init后isPrimary=$p0; 改主密码err=$e1, 改后isPrimary=$p1; 设应急密码err=$e2",
            e2,
        )
    }

    /** 路径 B：默认保护 → 解锁 → 设置主密码 → 设应急密码（用户最可能的路径） */
    @Test
    fun defaultThenSetMasterThenSetDuress() = runBlocking {
        repo.resetVault()
        repo.initVault(CryptoEngine.ANDROID_DEFAULT_MASTER, VaultMeta.MODE_DEFAULT)
        SessionManager.lock()
        val ok = repo.unlockDefault()
        val p0 = repo.isPrimary()
        val e1 = repo.changeMasterPassword(null, "master123456")
        val p1 = repo.isPrimary()
        val e2 = repo.setDuressPassword("duress123456")
        assertNull(
            "路径B: unlockDefault=$ok, 解锁后isPrimary=$p0; 设主密码err=$e1, 设后isPrimary=$p1; 设应急密码err=$e2",
            e2,
        )
    }

    /** 路径 C：custom 初始化 → 立即设应急密码（对照组，应成功） */
    @Test
    fun setDuressRightAfterInit() = runBlocking {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
        val p0 = repo.isPrimary()
        val e2 = repo.setDuressPassword("duress123456")
        assertNull(
            "路径C: init后isPrimary=$p0; 设应急密码err=$e2",
            e2,
        )
    }

    /**
     * 路径 D：主密码与应急密码相同时，两槽位会被同一密码同时解开。
     * 若 unlock 无条件优先选 Slot.A，而真库恰好在 Slot.B，用户就被送进影子库：
     * isPrimary() 恒 false → 再设应急密码报「当前会话不支持此操作」。
     *
     * 真库槽位由 init 随机决定，故循环多次以覆盖真库落在 B 的情况。
     */
    @Test
    fun unlockWithSharedPasswordMustLandOnPrimary() = runBlocking {
        for (i in 1..8) {
            repo.resetVault()
            repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
            val e0 = repo.setDuressPassword("master123456") // 应急密码 == 主密码
            SessionManager.lock()
            val ok = repo.unlock("master123456")
            val p = repo.isPrimary()
            assertTrue(
                "第 $i 次: 两槽位同密码时用主密码解锁必须落在主库(setDuress err=$e0, unlock=$ok, isPrimary=$p)",
                p,
            )
        }
    }

    /** 路径 E：应急密码不得与主密码相同（源头杜绝两槽位同密码） */
    @Test
    fun setDuressSameAsMasterMustBeRejected() = runBlocking {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
        val err = repo.setDuressPassword("master123456")
        assertTrue("应急密码与主密码相同应被拒绝，实际 err=$err", err != null)
    }

    /** 路径 F：改主密码时，新主密码不得与已有应急密码相同 */
    @Test
    fun changeMasterToDuressPasswordMustBeRejected() = runBlocking {
        repo.resetVault()
        repo.initVault("master123456", VaultMeta.MODE_CUSTOM)
        val e0 = repo.setDuressPassword("duress123456")
        assertNull("先设一个不同的应急密码应成功，实际 err=$e0", e0)
        val err = repo.changeMasterPassword("master123456", "duress123456")
        assertTrue("新主密码与应急密码相同应被拒绝，实际 err=$err", err != null)
    }

    /**
     * 路径 G：模拟「历史遗留的两槽位同密码」数据 —— 即本次 bug 的用户现场。
     *
     * 由于同密现在已被 setDuressPassword / changeMasterPassword 封堵，无法再经由
     * 正常 API 造出该状态，故直接用 VaultStore 手工构造：让 peer 槽位也能被主密码解开。
     * 验证 unlock 的同密码分支能凭 aux 单向性挑出主库，使 isPrimary() 恢复 true。
     */
    @Test
    fun legacySharedPasswordState_unlockMustLandOnPrimary() = runBlocking {
        val master = "master123456"
        repo.resetVault()
        repo.initVault(master, VaultMeta.MODE_CUSTOM)
        val real = SessionManager.requireSlot() // init 后会话所在即真库槽位
        val peer = real.other()
        val store = VaultStore(app)

        val realMeta = store.readMeta(real)!!
        val keyR = CryptoEngine.deriveKeyB64(master, realMeta.saltB64)

        // 让 peer 槽位也被 master 解开（等价于"应急密码 == 主密码"的历史数据）
        val peerSalt = CryptoEngine.randomSaltB64()
        val keyP = CryptoEngine.deriveKeyB64(master, peerSalt)
        val (vP, vPIv) = CryptoEngine.makeVerify(keyP)
        val decoyP = CryptoEngine.aesEncrypt(CryptoEngine.randomHex(32), keyP)
        store.writeMeta(
            peer,
            realMeta.copy(
                saltB64 = peerSalt,
                verifyB64 = vP,
                verifyIvB64 = vPIv,
                auxSaltB64 = realMeta.saltB64,
                auxSecretEnc = decoyP.dataB64,
                auxSecretIv = decoyP.ivB64,
            ),
        )
        // 真库 aux 保存"应急密码"（此处恰等于主密码），用真库密钥加密
        val auxReal = CryptoEngine.aesEncrypt(master, keyR)
        store.writeMeta(
            real,
            realMeta.copy(
                auxSaltB64 = peerSalt,
                auxSecretEnc = auxReal.dataB64,
                auxSecretIv = auxReal.ivB64,
            ),
        )

        SessionManager.lock()
        val ok = repo.unlock(master)
        val p = repo.isPrimary()
        assertTrue("遗留同密码数据：用主密码解锁必须落在主库(unlock=$ok, isPrimary=$p)", p)
        val err = repo.setDuressPassword("duress123456")
        assertNull("遗留同密码数据：重设应急密码应成功(isPrimary=$p)，实际 err=$err", err)
    }
}
