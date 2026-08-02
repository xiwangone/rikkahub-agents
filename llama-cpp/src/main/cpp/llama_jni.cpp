#include <jni.h>
#include <sstream>
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

extern "C" JNIEXPORT jlong JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeLoadModel(JNIEnv *env, jobject, jstring pathIn) {
    JNI_GUARD(env, 0L, {
        llama_backend_init();
        const std::string path = jstringToUtf8(env, pathIn);

        llama_model_params params = llama_model_default_params();
        // CPU only for now; the spec keeps GPU offload behind a CMake option.
        params.n_gpu_layers = 0;
        // Default params already select mmap; set it explicitly since the field this
        // used to be (a plain use_mmap bool) was replaced by this enum in b10228.
        params.load_mode = LLAMA_LOAD_MODE_MMAP;

        llama_model *model = llama_model_load_from_file(path.c_str(), params);
        if (model == nullptr) {
            throwJava(env, ("failed to load model: " + path).c_str());
            return 0L;
        }
        return reinterpret_cast<jlong>(model);
    })
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeFreeModel(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0L) {
        return;
    }
    llama_model_free(reinterpret_cast<llama_model *>(handle));
}

// Reads one integer-valued metadata key, trying the architecture-prefixed name first.
// Only used for the head dimensions: llama.cpp exposes typed accessors for everything
// else this file needs (head_count_kv, head_count, sliding window).
static int metaInt(const llama_model *model, const std::string &suffix, int fallback) {
    char arch[128] = {0};
    llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));

    char buf[128] = {0};
    const std::string key = std::string(arch) + "." + suffix;
    if (llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf)) > 0) {
        try {
            return std::stoi(buf);
        } catch (...) {
            return fallback;
        }
    }
    return fallback;
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD(env, nullptr, {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (model == nullptr) {
            throwJava(env, "model handle is null");
            return nullptr;
        }

        const int nEmbd     = llama_model_n_embd(model);
        const int nLayers   = llama_model_n_layer(model);
        const int nCtxTrain = llama_model_n_ctx_train(model);
        const int nHeadKv   = llama_model_n_head_kv(model);
        const int nHeadCnt  = llama_model_n_head(model);
        const int slidingWindow = llama_model_n_swa(model);

        // Head dims are declared on newer models and derivable on older ones. There is no
        // typed accessor for them, so read the metadata key directly.
        int headK = metaInt(model, "attention.key_length", 0);
        int headV = metaInt(model, "attention.value_length", 0);
        if (headK == 0 && nHeadCnt > 0) {
            headK = nEmbd / nHeadCnt;
        }
        if (headV == 0 && nHeadCnt > 0) {
            headV = nEmbd / nHeadCnt;
        }

        const llama_vocab *vocab = llama_model_get_vocab(model);

        std::ostringstream out;
        out << "{"
            << "\"n_layers\":"       << nLayers                        << ","
            << "\"n_embd\":"         << nEmbd                          << ","
            << "\"n_head_kv\":"      << nHeadKv                        << ","
            << "\"n_embd_head_k\":"  << headK                          << ","
            << "\"n_embd_head_v\":"  << headV                          << ","
            << "\"n_vocab\":"        << llama_vocab_n_tokens(vocab)    << ","
            << "\"n_ctx_train\":"    << nCtxTrain                      << ","
            << "\"sliding_window\":" << slidingWindow                  << ","
            << "\"weights_bytes\":"  << llama_model_size(model)
            << "}";
        return env->NewStringUTF(out.str().c_str());
    })
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeChatTemplate(JNIEnv *env, jobject, jlong handle) {
    JNI_GUARD(env, nullptr, {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (model == nullptr) {
            return nullptr;
        }
        const char *tmpl = llama_model_chat_template(model, nullptr);
        return tmpl == nullptr ? nullptr : env->NewStringUTF(tmpl);
    })
}
