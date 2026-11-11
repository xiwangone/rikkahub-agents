plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.terminal"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        // 原库单测会碰到 android.util.Base64 等 Android 类, 沿用原库的设置让它们返回默认值
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // BidiSpoofing 检查在解析 JNI.java 的 JavaDoc 时崩溃：lint 内嵌的 PSI
        // 调了 Java 21 才有的 List.removeLast()，JDK 17 上不存在
        // （NoSuchMethodError，lint 自身的问题，与代码无关），按 lint 的建议禁用。
        disable.add("BidiSpoofing")
    }
}

dependencies {
    testImplementation(libs.junit)
}
