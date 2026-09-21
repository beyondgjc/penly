package com.beyondguo.penly.crypto

/**
 * KDF 参数档位（#18）——**选档不选数字**。
 *
 * 为什么需要这一层：Argon2id 的 `memoryKiB / iterations / parallelism` 是三个
 * 必须一起调的数字，调用方随手填很容易得到"看起来安全但实际很弱"的组合
 * （如 memory=8MiB 配 iterations=1——在 GPU 上几乎免费）。
 * 档位把这三个数字绑成一组经过考量的预设，把误用面收窄成"选哪一档"。
 *
 * ## 职责边界（重要）
 * profile 只管**输入侧"怎么选参数"**，**绝不写进数据**。
 * 落盘的是它展开后的显式数值（见 [KdfSpec]）——这样将来调整档位定义，
 * 不会让已存在的数据解不开（老数据带着自己的参数走）。
 *
 * ## 与跨端契约的关系
 * 跨端备份契约的参数（`32 MiB / t=4 / p=1`）属于**两端既定的交换格式**，
 * 不放进档位表——它是格式的一部分，不是"给新库选参数"的选项。
 *
 * @param id 稳定标识（用于日志 / 文档 / 配置读写；**不进数据**）
 */
enum class Profile(
    val id: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    /** 32 MiB —— 交互式场景：派生耗时约百毫秒级，适合频繁解锁 */
    INTERACTIVE("interactive", 32 * 1024, 3, 1),

    /**
     * 64 MiB —— 敏感数据默认档。
     *
     * **数值与 penly 本机 KEK 现用值一字不差**（原 DoubleEnvelope 的
     * `KEK_MEMORY_KIB/KEK_ITERATIONS/KEK_PARALLELISM`）——改成别的值会让
     * 所有存量信封解不开。见 [ProfileTest] 的一致性断言。
     */
    SENSITIVE("sensitive", 64 * 1024, 3, 1),

    /** 256 MiB —— 高价值秘密：派生耗时秒级，适合"解锁一次用很久"的场景 */
    PARANOID("paranoid", 256 * 1024, 6, 1),
    ;

    /**
     * 按本档位参数做 Argon2id 派生。
     *
     * @param password 主密码字节（UTF-8）
     * @param salt 每库独立的盐（16B 随机，见 [Container.createHeader]）
     */
    fun derive(password: ByteArray, salt: ByteArray, outLen: Int = 32): ByteArray =
        Aead.argon2id(
            password = password,
            salt = salt,
            memoryKiB = memoryKiB,
            iterations = iterations,
            parallelism = parallelism,
            outLen = outLen,
        )

    /** 展开成可落盘的显式参数（数据自描述，见 [KdfSpec]） */
    fun toSpec(saltB64: String): KdfSpec = KdfSpec(
        alg = Container.KDF_ARGON2ID,
        saltB64 = saltB64,
        memoryKiB = memoryKiB,
        iterations = iterations,
        parallelism = parallelism,
    )

    companion object {
        /** 按标识查档位；未知标识返回 null（**不猜、不降级**——由调用方决定怎么处理） */
        fun byId(id: String): Profile? = entries.firstOrNull { it.id == id }
    }
}
