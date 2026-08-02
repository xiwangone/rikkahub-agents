#pragma once

#include <jni.h>
#include <string>
#include <exception>

inline void throwJava(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

inline std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

// Wraps a JNI body so no C++ exception can cross the boundary.
#define JNI_GUARD(env, failValue, body)                          \
    try {                                                        \
        body                                                     \
    } catch (const std::exception &e) {                          \
        throwJava(env, e.what());                                \
        return failValue;                                        \
    } catch (...) {                                              \
        throwJava(env, "unknown native error");                  \
        return failValue;                                        \
    }
