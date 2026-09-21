package com.beyondguo.penly.crypto

/**
 * 加密内核新增的异常（2026-09-21，随 #17 容器引入）。
 *
 * 既有的五个异常**保持原位**（不搬动——搬动是纯重构，会波及大量引用而无收益）：
 * - `Aead.IntegrityException`      —— 密文认证失败（GCM tag / AAD 不符）
 * - `MacVerificationException`     —— 历史 v1 格式的记录 MAC 校验失败（`CryptoEngine.kt`）
 * - `WrongPasswordException`       —— 密码因素失败（`DoubleEnvelope.kt`）
 * - `KeyUnavailableException`      —— 设备因素失败（`DoubleEnvelope.kt`）
 * - `VaultLockedException`         —— 会话未建立（`KeySession.kt`）
 *
 * 调用方一律按**类型**分流，不要靠 message 文案判断（文案会变，类型不会）。
 */

/**
 * 容器格式不认识：未知的 `headerV` / `kdf.alg` / `dataAlg`。
 *
 * **这个异常的存在本身就是一条纪律**：遇到不认识的东西必须抛错，
 * 不能"猜一个最像的算法试试"或"降级到默认参数"——那会让错误密钥
 * 解出乱码被当成成功（本项目 AES-CBC 非 AEAD，错密钥有 ~1/256 概率
 * 过 padding 检查，静默降级的代价尤其高）。
 */
class UnsupportedFormatException(message: String) : Exception(message)
