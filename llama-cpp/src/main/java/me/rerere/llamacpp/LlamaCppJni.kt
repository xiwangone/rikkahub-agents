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

    /** Loads a GGUF and returns an opaque handle. Throws RuntimeException on failure. */
    external fun nativeLoadModel(path: String): Long

    /** Releases a handle from [nativeLoadModel]. Safe to call with 0. */
    external fun nativeFreeModel(handle: Long)

    /** Model metadata as a JSON object, shaped for [GgufModelInfo]. */
    external fun nativeModelInfo(handle: Long): String

    /** The model's own Jinja chat template, or null when it declares none. */
    external fun nativeChatTemplate(handle: Long): String?
}
