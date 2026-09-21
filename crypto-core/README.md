# crypto-core · 印迹加密内核

纯 JVM 的本地加密 SDK。把「一块数据只能被掌握主密码的人读到」这件事，做成一组**不需要懂密码学也不容易用错**的接口。

无 Android 依赖，无网络，无第三方密码学实现（除 BouncyCastle 的 Argon2id）。同一份代码在 Android、桌面 JVM、服务端跑出来的字节完全一致。

---

## 这是什么

印迹（penly）最初把加密代码写在 App 里。当第二个产品（本地记录 App「印迹记录」）也要加密时，面临选择：抄一份，还是抽出来。

选择抽出来，于是有了这个模块。它的存在意义只有一条：**同一套加密，两处复用**——而不是「一个库供多方共享密钥」。两个 App 各自主密码、各自密钥、各自备份，互不知道对方存在；共用的只是算法实现。

```
app (印迹 · 密码管理器)          :crypto-core             将来 (印迹记录 · 本地日记)
┌────────────────────┐      ┌──────────────────┐      ┌────────────────────┐
│ 双槽位/影子库/autofill │─────▶│  原语 + 容器 + 会话  │◀─────│   感受字段加密       │
│ VaultItem / Slot    │ 依赖  │  ⚠️ 不含任何产品语义 │ 依赖  │  Entry / Feeling    │
└────────────────────┘      └──────────────────┘      └────────────────────┘
      业务适配层                   加密能力                    业务适配层
```

**密钥不共享。** core 没有任何全局单例密钥、没有跨 App 存储、不碰 Keystore。所有密钥由宿主注入，会话由宿主管理。

---

## 快速开始

### 1. 接入

模块已在工程里（`settings.gradle.kts:27`），直接声明依赖：

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":crypto-core"))
}
```

包名是 `com.beyondguo.penly.crypto`。如果你是自己新建的工程，把 `crypto-core/` 目录拷过去，在 `settings.gradle.kts` 加 `include(":crypto-core")` 即可。**注意包名与工程名是解耦的**——`penly` 在包名里的历史包袱不影响使用。

需要的 Gradle 插件（两个模块都要）：

```kotlin
// 根 build.gradle.kts —— 这里必须显式声明 apply false，
// 否则 :app 的 kotlin.android 会把 KGP 以「版本未知」带上类路径，
// 子模块解析同 id 插件时报 already on the classpath with an unknown version
alias(libs.plugins.kotlin.jvm) apply false
alias(libs.plugins.kotlin.serialization) apply false
```

模块自带的依赖（你不需要重复声明）：

| 依赖 | 用途 |
| --- | --- |
| `org.bouncycastle:bcprov-jdk18on:1.78.1` | Argon2id（走轻量 API，不经 JCA provider，避开系统内置旧版 BC 冲突） |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1` | 库头的 JSON 编解码 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0`（`api`） | `KeySession.unlocked` 是 `StateFlow` |

jvmToolchain 11，与宿主对齐。

### 2. 建库（一次）

```kotlin
import com.beyondguo.penly.crypto.Container
import com.beyondguo.penly.crypto.Profile

// 选档位，不选数字
val header = Container.createHeader(Profile.SENSITIVE)
```

`header` 是**明文**的，没有秘密，直接存进你的 meta 表 / DataStore。它是一个 `@Serializable data class`：

```json
{
  "headerV": 1,
  "kdf": { "alg": "argon2id", "saltB64": "…", "memoryKiB": 65536, "iterations": 3, "parallelism": 1 },
  "dataAlg": "aes-256-gcm"
}
```

**为什么要存这些参数**：数据要能活十年。如果解密的参数只写在代码常量里，你哪天调了默认值，老数据就永远解不开了。容器把参数写进数据本身，老数据带着自己的参数走。

⚠️ 序列化时注意：`VaultHeader` 的 `alg` / `dataAlg` / `headerV` 都有默认值，而 kotlinx-serialization 默认 `encodeDefaults = false` —— **写库头时必须开 `encodeDefaults = true`**，否则这几个字段会从 JSON 里消失。

### 3. 解锁（每次进入）

```kotlin
import com.beyondguo.penly.crypto.KeySession

// 按库头里存的参数派生 —— 约 500ms（64MiB 档）
val key = Container.deriveKey(header, password.toByteArray(Charsets.UTF_8))
KeySession.establish(key)
```

**一次派生，长期持有。** 不要每条记录派生一次，也不要把 key 序列化到任何地方。`KeySession` 持有的是副本，`lock()` 时会把内存清零。

### 4. 读写字段（每次操作）

```kotlin
import com.beyondguo.penly.crypto.FieldCipher

val aad = FieldCipher.aad("feeling", entry.id)   // "feeling|<记录id>"
val ct  = FieldCipher.seal(key, aad, "其实有点孤独".toByteArray(Charsets.UTF_8))
// ct 是一个 Base64 字符串，直接存进你的字段

val text = String(FieldCipher.open(key, aad, ct), Charsets.UTF_8)
```

**核心需求就是这样。** 只加密「感受」就只加密那一个字段，其余所见所闻明文照旧，根本不需要经过 SDK。

### 5. 收尾

```kotlin
KeySession.lock()   // 零化密钥、释放引用、unlocked 置 false
```

UI 侧观察锁定状态：

```kotlin
KeySession.unlocked.collect { unlocked -> /* 显示锁屏/内容 */ }
```

---

## 接口全景

### L1 · 日常只用这些

#### `Profile` — 参数档位

```kotlin
enum class Profile(val id: String, val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    INTERACTIVE("interactive",  32 * 1024, 3, 1),   // 约百毫秒，适合频繁解锁
    SENSITIVE  ("sensitive",    64 * 1024, 3, 1),   // 默认档
    PARANOID   ("paranoid",    256 * 1024, 6, 1),   // 秒级，解锁一次用很久的场景
}
```

`derive(password, salt, outLen = 32)` · `toSpec(saltB64)` · `byId(id): Profile?`

Argon2id 的三个参数是**必须一起调**的。随手填很容易得到「看起来安全实际很弱」的组合（比如 memory=8MiB 配 iterations=1，在 GPU 上几乎免费）。档位把这三个数字绑成一组经过考量的预设，把误用面收窄成「选哪一档」。

档位只管**输入侧怎么选参数**，绝不写进数据——落盘的是它展开后的显式数值。这样将来调整档位定义，不会让已存在的数据解不开。

#### `Container` — 密文容器

```kotlin
object Container {
    fun createHeader(profile: Profile): VaultHeader
    fun deriveKey(header: VaultHeader, password: ByteArray): ByteArray
    fun seal(header: VaultHeader, key: ByteArray, aad: ByteArray, plaintext: ByteArray): FieldCiphertext
    fun open(header: VaultHeader, key: ByteArray, aad: ByteArray, ct: FieldCiphertext): ByteArray
}
```

配套三个 `@Serializable` 数据类：`KdfSpec` / `VaultHeader` / `FieldCiphertext`。

`seal` / `open` 是**字段粒度**的（一个字段一个 `FieldCiphertext`），因为它俩要读 `header.dataAlg` 做分派。如果你手里已经有 `key`，更常用的是下一节的 `FieldCipher`——它返回的是裸 Base64 字符串，更好塞进你的数据模型。

#### `FieldCipher` — 字段级加解密 ★推荐

```kotlin
object FieldCipher {
    fun aad(field: String, recordId: String): ByteArray      // "<字段名>|<记录id>"
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): String
    fun open(key: ByteArray, aad: ByteArray, payloadB64: String, algId: String? = null): ByteArray
    fun openOrThrowMac(key: ByteArray, aad: ByteArray, payloadB64: String, algId: String? = null): ByteArray
}
```

**大多数场景直接用这个。** 它只认 `key + AAD + 明文`，不认识任何宿主数据结构，所以能被任何 App 复用。

它存在的理由不是「少写几行循环」，而是把**三件必须每次都做对、漏掉即为静默强度下降**的事固化成一个入口：

| 收口的东西 | 漏掉的后果 |
| --- | --- |
| 域分离子密钥（`Aead.subKey(key, Domains.ENC)`） | 同一 key 在不同协议里复用，跨协议攻击面打开 |
| 逐字段 AAD 钉位 | 密文可以在字段之间、记录之间被搬运而不被发现 |
| 完整性异常归一（GCM 失败 → `MacVerificationException`） | 调用方漏 catch，fail-closed 纪律破功 |

`seal` 和 `open` 是「抛原始异常」版（`Aead.IntegrityException`）；`openOrThrowMac` 是「归一到 `MacVerificationException`」版——后者是为了兼容既有调用方的 catch 契约。新代码按你的异常处理风格挑一个就行，别两个混着用。

#### `KeySession` — 会话

```kotlin
object KeySession {
    fun establish(key: ByteArray)
    fun requireKey(): ByteArray          // 未解锁抛 VaultLockedException
    fun isUnlocked(): Boolean
    val unlocked: StateFlow<Boolean>
    fun lock()
}
```

进程内单例。`establish` 存的是**副本**（并对传入数组不持有引用），`lock` 会 `fill(0)` 清零后释放。

⚠️ 它是进程级单例——如果你的宿主有多个库/多用户，**不要把两个 key 同时塞进来**。这种场景请自己持有一个 key，或者用 `SessionManager` 那样的包装层（印迹就是这么做的，见下节）。

### L2 · 需要自定义协议时才用

#### `Aead` — 原语集

```kotlin
object Aead {
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray
    fun subKey(key32: ByteArray, domain: String): ByteArray
    fun gcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
    fun gcmDecrypt(key: ByteArray, payload: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
    fun argon2id(password, salt, memoryKiB, iterations, parallelism, outLen = 32, secret, associatedData): ByteArray
    fun b64(bytes: ByteArray): String
    fun unb64(s: String): ByteArray

    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    class IntegrityException(message: String = "数据完整性校验失败") : Exception(message)
    object Domains { const val ENC = "yinji-enc-v2" }
}
```

GCM 载荷布局：`nonce(12B) ‖ ciphertext ‖ tag(16B)`，不分段。

⚠️ **`Domains.ENC` 的值是线上格式，不可变更。** 字符串 `"yinji-enc-v2"` 用来派生子密钥，改值等于让所有存量数据解不开。要加新用途就注册新标签，**不要复用**。

#### `DoubleEnvelope` — 双层密钥信封

```kotlin
object DoubleEnvelope {
    fun seal(wrapper: KeyWrapper, masterPassword: ByteArray, key32: ByteArray): Envelope
    fun unseal(wrapper: KeyWrapper, masterPassword: ByteArray, envelope: Envelope): ByteArray

    @Serializable data class Envelope(
        val saltLocalB64: String,
        val payloadB64: String,
        val wrapperTag: String,
    )
}
```

把 `key32` 用「设备因素 × 密码因素」两层共同保护：

```
inner = TEE.wrap(key32)        设备因素：TEE 离线解不出
outer = GCM(KEK, inner)        密码因素：KEK = Argon2id(主密码, 本机盐)
```

严格双因素：拿到密码没设备 ✗，拿到设备没密码 ✗，两者都有 → 放行。**没有单因素后门**，也没有恢复码——换机 / 恢复出厂后 TEE 密钥被销毁，信封永久不可解，唯一出路是从备份恢复。

失败语义严格分层，调用方必须区分：

| 异常 | 含义 | UI 应对 |
| --- | --- | --- |
| `WrongPasswordException` | outer 解不开 = 密码错（或 payload 被篡改） | 普通重试 |
| `KeyUnavailableException` | outer 解开了但设备层失败 | 专用降级流程：走备份恢复 |

⚠️ 产品配套：**启用信封前应强制完成一次导出检查**。否则用户可能在一个没备份过的库上开启信封，然后换机丢数据。

#### `KeyWrapper` — 平台接缝

```kotlin
interface KeyWrapper {
    val tag: String                                    // "keystore" / 自定义
    fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun unwrap(payload: ByteArray, aad: ByteArray): ByteArray
}
```

**这是 core 与宿主平台之间唯一的接缝。** core 只声明它，实现由宿主提供：

- 生产环境：`AndroidKeyStore` 实现（TEE / StrongBox 硬件保护），印迹在 `app/` 里维护
- 测试环境：软件实现，JVM 单测直接跑信封语义（`DoubleEnvelopeTest` 就是这么验的）
- 其它平台：任何有硬件密钥的介质都能接（Secure Enclave、TPM…）

载荷格式与 `Aead` 一致：`nonce(12B) ‖ ciphertext ‖ tag(16B)`。

#### `Shamir` — 门限分片

```kotlin
object Shamir {
    class Share(val x: Int, val y: ByteArray)
    fun split(secret: ByteArray, threshold: Int, shares: Int, random: SecureRandom = SecureRandom()): List<Share>
    fun combine(shares: List<Share>): ByteArray
    fun shareText(s: Share): String     // "YJH1<坐标>-<b64url(y)>"
    fun parseText(text: String): Share  // 容忍首尾空白与全角连字符

    const val SHARE_PREFIX = "YJH1"
}
```

GF(2^8)（多项式 0x11D）上的 Shamir 秘密共享。典型用法 2-of-3：任意 2 份可重建，单份泄露在信息论上不泄露任何秘密，丢失 1 份无需轮换。

**安全边界（诚实声明）**：分片本身无认证——错误或篡改的分片组合出的秘密必然是错的。但外层如果是 GCM（如印迹的恢复包），错误密钥解密必然 `IntegrityException`，fail-closed 由外层兜底，不会出现「错误密钥解出乱码数据」。**任何 2 份合谋 = 完整恢复能力**，分片必须放在互不共谋的位置。

#### `Totp` — 动态口令

```kotlin
object Totp {
    fun generate(secret: ByteArray, timeSeconds: Long, digits: Int = 6, period: Int = 30, algo: String = "SHA1"): String
    fun generate(secretBase32: String, timeSeconds: Long = now(), digits, period, algo): String
    fun parseInput(raw: String): Params
    fun resolveEditParams(raw: String, storedDigits: Int, storedPeriod: Int, storedAlgo: String): Params
    fun base32Decode(input: String): ByteArray
}
```

RFC 6238，零第三方依赖，正确性由 RFC 官方测试向量锚定。

`parseInput` 接受裸 base32 串或完整 `otpauth://totp/...` 链接。**越界参数一律抛错，绝不静默回落默认值**——静默回落会生成「看似能用但永远对不上」的码，用户无从发现。同理，`otpauth://hotp/` 会被拒绝（计数器型，无 period 语义），`FIDO:/` 开头的 Passkey 跨设备码也会被识别并给出专属提示。

---

## 异常总表

**调用方一律按类型分流，不要靠 message 文案判断**（文案会变，类型不会）。

| 异常 | 定义位置 | 含义 |
| --- | --- | --- |
| `Aead.IntegrityException` | `Aead.kt` | GCM tag / AAD 不符 —— 密文被篡改或密钥错 |
| `MacVerificationException` | `CryptoEngine.kt` | 完整性校验失败（历史 v1 MAC + 经 `openOrThrowMac` 的 GCM 归一） |
| `WrongPasswordException` | `DoubleEnvelope.kt` | 密码因素失败 |
| `KeyUnavailableException` | `DoubleEnvelope.kt` | 设备因素失败 → 唯一出路是备份恢复 |
| `VaultLockedException` | `KeySession.kt` | 未解锁就访问会话密钥 |
| `UnsupportedFormatException` | `Exceptions.kt` | 容器版本 / 算法不认识 |

`UnsupportedFormatException` 的存在本身就是一条纪律：**遇到不认识的东西必须抛错**，不能猜一个最像的算法试试，也不能降级到默认参数。本项目的历史格式用 AES-CBC（非 AEAD），错误密钥有约 1/256 概率通过 padding 检查——静默降级的代价是「乱码被当成成功」。

---

## 设计纪律

这几条是这个 SDK 的使用契约，不是建议。

**1. fail-closed，不猜不降级。**
不认识就抛错。解不开就中断整个操作，磁盘保持原状。绝不「跳过坏的继续」——那会把损坏静默固化成看似正常的数据。改密 / 导入重加密途中遇到完整性失败，必须中止。

**2. AAD 必须填。**
`seal` 的 `aad` 参数留空不会报错，但那等于放弃防搬运保护：攻击者可以把 A 记录的秘密字段密文搬到 B 记录的账户字段，只要 key 相同就能解开。惯例格式 `"<字段名>|<记录id>"`（`FieldCipher.aad()` 就是这个），它让本机存储与备份文件在 AAD 语义上天然同构——同一份数据换载体不会解不开。

**3. 一次派生，长期持有。**
Argon2id 64MiB 档一次约 500ms。库级 salt 派生一次得 key32，缓存在会话里；记录级只用 HKDF 子密钥 + 每字段独立随机 nonce。不要每条记录派生一次。

**4. 域分离，不复用标签。**
不同用途必须用不同子密钥（`Aead.subKey` + 独立的 `Domains` 标签）。既有标签的值是线上格式，只能读不能改。

**5. 密钥不落盘、不进日志。**
`KeySession` 只在内存持有，`lock()` 清零。当前槽位相关的任何东西都不写日志（release 包已剥 `android.util.Log`，但源码层仍要注意）。

**6. 分层：字段级原语收口到 SDK，条目级结构适配留在宿主。**

这条是 Phase 1 定下来的边界，也是最容易被越界的一条：

| 收在 SDK 里 | 留在宿主 |
| --- | --- |
| 子密钥派生、AAD 拼装、异常归一、算法分派、参数自描述 | 数据模型（`VaultItem` / `Entry`）、双槽位、影子库、跨端备份格式、业务常量 |

宿主加密新字段时**直接用 `FieldCipher`**，不要再自己拼 `Aead` 原语。

反面教材：印迹的 `ItemCipher` 是 `FieldCipher` 的业务适配层（`VaultItem` 五字段 + V2/V3 分派 + CBC 四元组载体），它**有意未**委托 `FieldCipher.reEncryptRecord`——因为 CBC 的 enc/iv/mac 三平行列套 Map 需要两次来回转换，比直白写法更复杂也更易错。**不是所有复用都值得做**，判断标准是「是否降低出错概率」，不是「是否消除了重复代码」。

---

## 明确不提供

界外的东西不会加进来，别等：

| 不提供 | 原因 |
| --- | --- |
| 数据模型（`VaultItem` / `PlainEntry` / `Slot`） | 产品语义，每个宿主不同 |
| 双槽位、影子库、原子性提交 | 密码管理器的产品机制 |
| 跨端备份格式（`BackupCodec`） | 已是既定交换格式，由各端自持 |
| 业务常量（默认主密码、`masterRef` 值） | 印迹专用 |
| 加密索引 / 加密检索 | 明确不做（检索用明文子集或内存索引） |
| 大文件 / 媒体流式加解密 | 场景不需要 |
| 跨 App 密钥共享 | 架构上反对——每个 App 独立密钥 |
| 自动锁定粘合 | 生命周期是宿主的（印迹走 `PenlyApp` 的 started 计数） |
| 任何全局密钥单例 | 密钥必须由宿主注入 |

---

## 构建与测试

```bash
# 只跑 SDK 单测（96 例，约 10 秒，不需要连设备）
./gradlew :crypto-core:test

# 宿主 App 的单测（加密面 + 检索评测）
./gradlew :app:testDebugUnitTest

# 真机 / 模拟器设备测试
./gradlew :app:connectedDebugAndroidTest

# release 混淆包回归（R8 优化包上跑设备测试）
./gradlew :app:connectedReleaseAndroidTest -PtestBuildType=release
```

测试覆盖：

| 测试类 | 例数 | 覆盖 |
| --- | --- | --- |
| `ContainerTest` | 17 | 库头生成、自描述参数、版本/算法拒绝、AAD 隔离 |
| `ContractV2VectorTest` | 6 | 跨端固定向量（与小程序的字节级互认闸门） |
| `FieldCipherTest` | 21 | 往返、AAD 跨字段/跨记录搬移拒收、异常归一、批重加密语义 |
| `DoubleEnvelopeTest` | 6 | 双因素语义、失败分层（软件 `KeyWrapper`） |
| `ProfileTest` | 8 | 档位参数、`byId`、与 penly KEK 数值一致性 |
| `TotpTest` | 29 | RFC 6238 官方向量、链接解析、越界拒绝、编辑回存 |
| `ShamirTest` | 9 | 分片重建、阈值语义、文本往返 |

合计 **96 例**。

**固定向量是跨端兼容的闸门。** 改动任何加密路径前先跑 `ContractV2VectorTest`。

### R8 / ProGuard

如果宿主开了混淆，core 里的类需要保留规则。印迹的 `app/proguard-rules.pro` 里是：

```proguard
-keep class com.beyondguo.penly.crypto.** { *; }
```

ProGuard 的 `-keep` 按包名匹配、**跨模块生效**——R8 不区分类的来源模块，所以这一条同时覆盖了独立 jar（core）里的类。已由 release 回归（24/24）实证。

另外两点，是你接入时可能踩到的：

- release 包剥日志靠 `-assumenosideeffects android.util.Log{v/d/i/w/e/wtf}`，它依赖「返回值无人使用」。源码层别把 `Log.x(...)` 的返回值赋给变量。
- `Aead.IntegrityException` 保持为嵌套类是有意的（既有代码大量使用 `Aead.IntegrityException` 形式），别去「优化」它。

---

## 与宿主的关系（印迹是怎么用的）

印迹的 `app/` 侧是这套 SDK 的一个完整宿主示例，几个值得参考的点：

**`SessionManager`** —— `KeySession` 只存密钥，不管业务槽位。印迹需要知道「当前解锁的是哪个槽位」（双槽位/影子库架构），于是写了 `SessionManager` 做包装：

```kotlin
object SessionManager {
    @Volatile private var slot: Slot? = null
    val unlocked: StateFlow<Boolean> get() = KeySession.unlocked
    fun establish(newKey: ByteArray, newSlot: Slot) { slot = newSlot; KeySession.establish(newKey) }
    fun requireSlot(): Slot = slot ?: throw VaultLockedException()
    fun lock() { slot = null; KeySession.lock() }
}
```

**`ItemCipher`** —— 业务适配层，把 `VaultItem` 的具名字段 × V2/V3 双格式分派套在 `FieldCipher` 之上。

**`AndroidKeyStoreWrapper`** —— `KeyWrapper` 的生产实现，走 TEE 硬件保护，支持 StrongBox 偏好与可用性探测。

**自描述容器的对齐先例** —— 跨端备份的 `BackupCodecV2` 早就在从文件里的 `kdf` 字段读回 `saltB64 / memoryKiB / iterations / parallelism`。`Container` 的 `KdfSpec` 就是把这个做法抽成通用组件，**字段结构与之对齐**（同名同义），不另创一套。

---

## 与既有格式的关系

`CryptoEngine` 是 SDK 里的 **v1 兼容档**，服务存量数据与跨端备份契约（PBKDF2-SHA256 10 万次 + AES-256-CBC + encrypt-then-MAC）。

- **新代码不要用它的 CBC 路径。**
- 它会被保留到所有 v1 数据完成迁移，之后随契约 v1 一起冻结。
- 里面的 `macSubKey` 有一处**反直觉但必须照抄**的实参顺序：`HMAC-SHA256(key=utf8("yinji-record-mac-v1"), msg=key32)`——信息串作 HMAC key、密钥作 message。这是已部署的线上格式，不得「顺手修正」。

---

## License

Apache License 2.0 · © 2026 BeyondGuo
