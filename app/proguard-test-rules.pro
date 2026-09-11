# ============================================================
# 印迹 penly · androidTest 测试包专用 R8 规则
# 挂载点：release buildType 的 testProguardFiles()（测试 APK 的 R8
# 只读这个通道，不读 proguard-rules.pro——2026-09-10 实测确认）
# ============================================================

# androidx.test 内部引用 compile-only 的 errorprone 注解，其 jar 不带运行时类，
# 测试包跑 R8 时报 Missing class——dontwarn 抑制（missing_rules.txt 同款）
-dontwarn com.google.errorprone.annotations.**
# errorprone 注解体系还会引用 JDK compile-only 的 javax.lang.model（Android 无此类）
-dontwarn javax.lang.model.element.**

# ---- 跨 APK 混淆命名一致性（关键）----
# AGP 对 app 与 androidTest 各跑一次相互独立的 R8：测试包里自带的库副本
# （如 coroutines）会被测试包 R8 起一套自造名（实测 i2.h），运行时类加载
# 优先命中 app 包里同名但不同成员签名的类 → NoSuchMethodError，instrumentation
# 在 newApplication 阶段即崩（AppComponentFactoryRegistry.instantiateApplication）。
# 用 app 的 mapping.txt 复用同一套命名即可对齐；minifyReleaseWithR8 在任务图
# 上保证先于测试包 R8 执行，产物路径按本文件（app/）相对定位。
-applymapping build/outputs/mapping/release/mapping.txt
