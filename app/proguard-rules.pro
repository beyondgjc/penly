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
