#include <jni.h>
#include <string>

#include "llama.h"
#include "jni_util.h"

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeSystemInfo(JNIEnv *env, jobject) {
    JNI_GUARD(env, nullptr, {
        llama_backend_init();
        const std::string info = llama_print_system_info();
        return env->NewStringUTF(info.c_str());
    })
}
