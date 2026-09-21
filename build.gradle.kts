// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    configurations.all {
        // AGP（插件 classpath）传递带入旧版 bouncycastle，强制统一到安全版本
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpg-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86",
        )
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.test) apply false
}
