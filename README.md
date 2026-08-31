# 印迹 (印迹)

端到端加密的本地印迹 Android 客户端。账号、密码与备注全部在设备本地加密存储，主密码不上传、不落地服务端，零知识（zero-knowledge）设计。

> 代码名 **印迹**，包名 `com.beyondguo.penly`，应用显示名「印迹」。

## 特性

- **本地端到端加密**：所有条目（账号、密码、备注）均以主密码派生的密钥在本地加密，主密码本身不存储、不上传。
- **生物识别解锁**：支持指纹 / 面容（Android Biometric）快速解锁。
- **备份与恢复**：支持从备份文件（JSON）导入或覆盖本机数据。
- **跨端互认**：加密方案与配套的微信小程序端严格字节级互认，可在多端之间安全迁移数据。

## 安全设计

加密契约（任何一端不得单方变更）：

| 环节 | 方案 |
| --- | --- |
| 密钥派生 | PBKDF2-HMAC-SHA256，100,000 次迭代，16 字节随机 salt，输出 32 字节密钥 |
| 对称加密 | AES-256-CBC（PKCS5/7 padding），每个字段独立 16 字节随机 IV |
| 编码 | Base64 标准字母表（含 padding） |
| 口令校验 | 用派生密钥加密固定明文 token，解锁时本地解密比对（零知识，主密码不存储） |

- 主密码即根密钥：**遗忘主密码将无法恢复数据**，请务必牢记。
- 应用声明 `android:allowBackup="false"`，并仅申请 `USE_BIOMETRIC` 权限，无网络、无多余权限。
- 当前版本内置一个默认主密码仅用于快速体验；正式保存私密数据前，请在设置中设定你自己的主密码。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- AndroidX：Navigation Compose、DataStore(Preferences)、Biometric
- kotlinx.serialization（JSON）
- 纯 JVM 加密实现（`crypto/CryptoEngine.kt`），无 Android 依赖，便于单元测试
- 构建：Gradle Kotlin DSL + Version Catalog；AGP 8.7.x

## 环境要求

- **JDK 17+**（推荐直接用 Android Studio 自带的 JBR）
- Android Studio Hedgehog 或更高
- 最低运行版本：Android 10（API 29）；目标版本：API 34

## 构建与运行

```bash
# 使用 Android Studio 打开本仓库；或用命令行：
./gradlew :app:installDebug      # 安装到已连接设备 / 模拟器
./gradlew :app:assembleRelease   # 生成 release APK / AAB
```

> 说明：`app/build.gradle.kts` 的 release 构建当前复用 debug 签名，正式发布前请替换为你的正式签名配置。

## 项目结构（节选）

```
app/src/main/java/com/beyondguo/penly/
├── crypto/      # CryptoEngine：密钥派生与 AES 加解密
├── bio/         # 生物识别管理
├── data/        # 数据模型与本地存储（VaultRepository / VaultStore）
├── backup/      # 备份导入 / 导出（JSON）
├── ui/          # Compose 界面（screens / components / theme）
└── util/        # 通用工具
tools/           # 跨端加密向量测试脚本（gen_test_fixtures.mjs）
```

## 开源协议

本项目以 [Apache License 2.0](LICENSE) 发布。© 2026 BeyondGuo.
