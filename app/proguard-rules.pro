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
