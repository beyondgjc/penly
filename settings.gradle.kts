pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Penly"
include(":app")
// 加密内核（纯 JVM，无 Android 依赖）：密码学原语 + 通用模式，供 penly 与
// 将来的「印迹记录」App 复用。见工作区《印迹加密SDK_Phase0技术方案.md》。
// 之所以是独立模块而非 app 内的包：它是 SDK 组件，先在 app 里写将来还得搬。
include(":crypto-core")
 