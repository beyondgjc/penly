# ============================================================
# 印迹 penly · release 混淆规则（v2.0.0 正式版起维护）
# ============================================================

# 崩溃栈还原：保留行号，配合归档的 mapping.txt（release/vX.Y.Z/ 下随版本归档，勿公开）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- ONNX Runtime（端内 AI 检索）----
# native 层按"类名 + 方法名"做 JNI 符号查找，R8 改名即断链；
# 模型 IO（OnnxTensor / OrtSession / OrtEnvironment）API 面必须完整 —— 整包保留
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---- kotlinx-serialization（data/Models.kt + backup/BackupCodec.kt 共 7 个 @Serializable 类）----
# 编译期生成的 *$$serializer 与 Companion.serializer() 工厂若被改名/裁剪，
# 运行时序列化直接失败 —— 按 kotlinx.serialization 官方推荐规则保留本项目包内生成物
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.beyondguo.penly.**$$serializer { *; }
-keepclassmembers class com.beyondguo.penly.** {
    *** Companion;
}
-keepclasseswithmembers class com.beyondguo.penly.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 无需 keep 的依赖（记录判断依据，防后人误加/误删）----
# pinyin4j：纯查表实现，无反射/JNI
# DataStore preferences / Compose / fragment / biometric / coroutines：均自带 consumer rules

# ---- kotlin stdlib 校验类：跨 APK 混淆对齐的钉子（2026-09-18）----
# AGP 8.9.1 的 R8 对 kotlin.jvm.internal.Intrinsics 做了水平类合并+参数重排
# （mapping 里 checkNotNullParameter(Object,String) -> f 但 residual 签名是
# (String,Object)）：androidTest 包经 -applymapping 只对齐名字、参数顺序仍是
# 原始序 → 运行时 NoSuchMethodError，instrumentation 在 newApplication 即崩
# （AppComponentFactoryRegistry.instantiateApplication，Release 回归实测）。
# keep 住整包：不做合并/重排/改名，两端签名天然一致。体积代价 ~几 KB。
-keep class kotlin.jvm.internal.** { *; }

# ---- kotlin / kotlinx 全家族原名钉子（2026-09-20，扩展自上面 kotlin.jvm.internal）----
# androidTest R8 的 --lib 是「主包产物」：主包里被改名的类，原名在 library 中不存在，
# 而 androidTest 独有的库（androidx.test.platform 等）按原名引用它们 → 运行时
# NoClassDefFoundError（首爆 kotlin.LazyKt，TestDirCalculator.<init>；tracing 同款
# 已由下面的钉子修掉）。这些库的依赖链（kotlin.* / kotlinx.coroutines.*）不可能
# 枚举穷尽，故整族 keep：主包 mapping 全原名 → 测试包 applymapping/剔除零冲突，
# 原名类在主包 dex 恒可达。体积代价 ~2-3MB（91MB 的包无感），无安全影响（纯语言运行时）。
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# ---- Compose 编译器的 $stable 标记字段（2026-09-20）----
# androidTest 与主包同用 Compose 编译器：测试类的 <clinit> 按原名引用主包类的
# int $stable 字段（mapping 里没有被删字段的记录，测试包无法对齐一个不存在的
# 字段）。主包 R8 删掉该字段 → NoSuchFieldError（实测：RecordMacDeviceTest
# .<clinit> 引用 data 包混淆类的 $stable）。
# 注意 keepclassmembernames 只防改名不防删除（实测无效），必须 keepclassmembers
# 把字段钉成 root：不删不改名。类名照常混淆，测试包经 applymapping 对齐。
# 体积代价每类一个 int，可忽略。
-keepclassmembers class com.beyondguo.penly.** { int $stable; }

# ---- androidx.tracing：跨 APK 混淆对齐的钉子（2026-09-20）----
# androidx.test:runner 1.6.2 的 AndroidJUnitRunner.onCreate 直接调用
# androidx.tracing.Trace（其 pom 把 tracing 声明为 compileOnly 可选依赖）。
# v5.0 加 credentials 后 tracing 升至 2.0.0（Kotlin 重写，类变多），主包 mapping
# 里一批 tracing 改名记录（-> L1.a 等）经 -applymapping 强加给 androidTest R8，
# 与测试包自身命名空间冲突 → 测试包 R8 把整个 tracing 包牺牲掉 → runner 启动
# 即崩 NoClassDefFoundError，UTP 只静默报 0 tests（连报错都没有，极隐蔽，
# 全量 clean 重建后依旧复现，dex class_defs 解析实锤 0 个 tracing 类）。
# 主包整包 keep：mapping 全部写成原名，测试包 applymapping 零冲突，
# 配合 proguard-test-rules.pro 的同款 keep，测试 APK 稳定携带 tracing。
# 体积代价 ~百 KB 级（tracing 2.0 约 80 个类）。
-keep class androidx.tracing.** { *; }

# ---- androidTest 直连面整包 keep（2026-09-20）----
# 主包开优化（proguard-android-optimize.txt）后 R8 做参数收窄：VaultStore
# .<init>(Context) 在 dex 里的实际签名被收窄成 (PenlyApp)V、suspend 方法的
# Continuation 参数被收窄成 ContinuationImpl（mapping residualsignature 实锤）。
# -applymapping 只对齐名字、对不齐签名，测试 dex 按原签名调用 → 运行时
# NoSuchMethodError（实测 RecordMacDeviceTest.<init> → data.c.<init>；与 v4.0
# Intrinsics 参数重排同根，都是「签名级」变化，名字对齐救不了）。
# 逐成员钉不可穷尽（方法也能被收窄），故测试直连的自家包整包 keep：
# 原名 + 签名冻结。范围 = 全部设备测试 import 的自家包 + PenlyApp
# （app.repo 等属性访问也走原名 getter）。
#
# ⚠️ crypto 包跨模块（2026-09-21）：加密内核已抽到 :crypto-core 模块，
# 但**包名保持 com.beyondguo.penly.crypto**，故本条规则按包名匹配、
# 同时覆盖 app 侧（AndroidKeyStoreWrapper/SessionManager）与 core jar 侧
# （Aead/DoubleEnvelope/KeyWrapper/KeySession/Shamir/Totp/CryptoEngine）——
# R8 的 -keep 按包名作用，不区分来源模块。**包名一旦改动，本条必须同步。**
-keep class com.beyondguo.penly.PenlyApp { *; }
-keep class com.beyondguo.penly.data.** { *; }
-keep class com.beyondguo.penly.crypto.** { *; }
-keep class com.beyondguo.penly.security.** { *; }
-keep class com.beyondguo.penly.search.** { *; }

# ---- release 剥离 android.util.Log ----
# 为什么：logcat 对本应用是侧信道。VaultRepository 重建检索索引时会把"当前是 A 槽位
# 还是 B 槽位"打进 logcat（检索索引已重建：slot=A/B），任何有 adb 读日志权限的第三方
# 都能推断出保险库的写入活动。密码管理器不该在 release 里留下这类痕迹。
#
# 为什么用 -assumenosideeffects 而不是改源码：源码里的日志要保留，调试时还得用。
# 该规则只在 R8「优化」开启时才生效 —— release 用的是 proguard-android-optimize.txt，
# debug 未开 minify，所以 debug 包日志照常打印，调试不受任何影响。
#
# 安全性：全量 15 个 Log.d/Log.w 调用点均已核对，没有任何一处使用 Log 的返回值
# （Log.* 返回 int，此处全部作为语句丢弃），剥离不改变任何业务逻辑。
# 注意：这只移除调用点，字符串常量会随调用点一起被判定为不可达而消失；
# 若将来出现 `val x = Log.d(...)` 这类取值写法，本规则会导致 x 变成未定义值，需重新评估。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
