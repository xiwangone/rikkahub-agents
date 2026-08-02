package me.rerere.llamacpp

/**
 * The entire JNI surface for llama.cpp. Nothing else in the codebase calls native code
 * directly, so every native contract change is visible in this one file.
 */
object LlamaCppJni {

    init {
        System.loadLibrary("llamajni")
    }

    /** Which CPU features the loaded build detected, for diagnostics and bug reports. */
    external fun nativeSystemInfo(): String

    fun systemInfo(): String = nativeSystemInfo()
}
