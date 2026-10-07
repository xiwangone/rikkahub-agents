pluginManagement {
    includeBuild("build-logic")

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
        // 回退源：官方仓库不可达 / 限流（如 HTTP 429）时按顺序继续解析；
        // 官方源命中时不会产生额外请求。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // 回退源：官方仓库不可达 / 限流（如 HTTP 429）时按顺序继续解析
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "rikkahub"
include(":oauth")
include(":app")
include(":highlight")
include(":ai")
include(":local-llm")
include(":llama-cpp")
include(":search")
include(":speech")
include(":common")
include(":document")
include(":web")
include(":material3")
include(":workspace")
include(":agent-tools")
include(":videogen")
