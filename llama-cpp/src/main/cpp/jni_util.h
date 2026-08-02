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
    if (chars == nullptr) {
        return std::string();
    }
    // Releases the JVM-owned buffer even if constructing the std::string below throws,
    // since the destructor still runs during stack unwinding.
    struct ReleaseGuard {
        JNIEnv *env;
        jstring value;
        const char *chars;
        ~ReleaseGuard() { env->ReleaseStringUTFChars(value, chars); }
    } guard{env, value, chars};
    return std::string(chars);
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

// Same as JNI_GUARD, for void-returning entry points where there is no failValue.
#define JNI_GUARD_VOID(env, body)                                \
    try {                                                        \
        body                                                     \
    } catch (const std::exception &e) {                          \
        throwJava(env, e.what());                                \
    } catch (...) {                                              \
        throwJava(env, "unknown native error");                  \
    }
