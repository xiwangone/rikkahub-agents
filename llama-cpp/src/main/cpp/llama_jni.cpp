#include <jni.h>
#include <sstream>
#include <string>

#include "llama.h"
#include "jni_util.h"
#include "chat.h"
#include <nlohmann/json.hpp>

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
    JNI_GUARD_VOID(env, {
        if (handle == 0L) {
            return;
        }
        llama_model_free(reinterpret_cast<llama_model *>(handle));
    })
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
        // typed accessor for them, so read the metadata key directly. The nEmbd / nHeadCnt
        // fallback only holds without grouped-query attention (nHeadKv == nHeadCnt); with
        // GQA, such as Qwen3, Gemma 3, and several Llama derivatives, that ratio is simply
        // wrong. Leave a missing value as 0 there so isComplete catches it, rather than
        // handing the planner a plausible-looking but incorrect head dimension.
        int headK = metaInt(model, "attention.key_length", 0);
        int headV = metaInt(model, "attention.value_length", 0);
        if (headK == 0 && nHeadCnt > 0 && nHeadKv == nHeadCnt) {
            headK = nEmbd / nHeadCnt;
        }
        if (headV == 0 && nHeadCnt > 0 && nHeadKv == nHeadCnt) {
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
            throwJava(env, "model handle is null");
            return nullptr;
        }
        const char *tmpl = llama_model_chat_template(model, nullptr);
        return tmpl == nullptr ? nullptr : env->NewStringUTF(tmpl);
    })
}

// Emits the trigger type by name rather than the enum's positional ordinal
// (common_grammar_trigger_type has no explicit values), so a llama.cpp bump that inserts a new
// variant produces a visible lookup miss on the Kotlin side instead of silently relabelling
// every existing trigger.
static const char *grammarTriggerTypeName(common_grammar_trigger_type type) {
    switch (type) {
        case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:        return "token";
        case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:         return "word";
        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:      return "pattern";
        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL: return "pattern_full";
    }
    return "unknown";
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_rerere_llamacpp_LlamaCppJni_nativeApplyTemplate(
        JNIEnv *env, jobject, jlong handle, jbyteArray requestIn) {
    JNI_GUARD(env, nullptr, {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (model == nullptr) {
            throwJava(env, "model handle is null");
            return nullptr;
        }

        // Carried as a byte[], not a jstring: GetStringUTFChars would hand back Modified UTF-8
        // (see jni_util.h), which nlohmann's strict UTF-8 parser rejects for any request
        // containing a supplementary-plane character, e.g. an emoji in a chat message.
        const std::string requestStr = byteArrayToUtf8(env, requestIn);
        const nlohmann::ordered_json request = nlohmann::ordered_json::parse(requestStr);

        // Reads the Jinja template out of the GGUF itself. Passing "" does not fail on a
        // model with no stored template: common_chat_templates_init (common/chat.cpp) falls
        // back to a built-in ChatML template in that case rather than throwing. It only
        // throws if a template (fallback or the model's own) fails to parse, and that is
        // already turned into a Java exception by JNI_GUARD below.
        common_chat_templates_ptr tmpls = common_chat_templates_init(model, "");

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(request.at("messages"));
        if (request.contains("tools") && !request.at("tools").is_null()) {
            inputs.tools = common_chat_tools_parse_oaicompat(request.at("tools"));
        }
        inputs.add_generation_prompt = true;
        // use_jinja and enable_thinking keep their struct defaults (both true): use_jinja
        // is what makes tool declarations and thinking reach the template at all, and
        // enable_thinking matches what ChatDeltaTracker expects downstream, that the model
        // emits its own thinking block for reasoning_content to be split out of.

        const common_chat_params params = common_chat_templates_apply(tmpls.get(), inputs);

        nlohmann::ordered_json out;
        out["prompt"]             = params.prompt;
        out["grammar"]            = params.grammar;
        out["grammar_lazy"]       = params.grammar_lazy;
        out["additional_stops"]   = params.additional_stops;
        out["preserved_tokens"]   = params.preserved_tokens;
        out["thinking_start_tag"] = params.thinking_start_tag;
        out["thinking_end_tags"]  = params.thinking_end_tags;
        out["format"]             = common_chat_format_name(params.format);

        // trigger.token (for a COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN trigger) is not carried across:
        // every construction site in common/chat.cpp builds a "word" or "pattern" trigger today,
        // so nothing is lost, but a future "token" trigger would serialize with an empty value
        // and never fire.
        nlohmann::ordered_json triggers = nlohmann::ordered_json::array();
        for (const auto &trigger : params.grammar_triggers) {
            triggers.push_back({
                {"type",  grammarTriggerTypeName(trigger.type)},
                {"value", trigger.value},
            });
        }
        out["grammar_triggers"] = triggers;

        // Returned as a byte[], not a jstring: out.dump() is standard UTF-8, and NewStringUTF
        // requires Modified UTF-8, which does not allow the four-byte sequences a
        // supplementary-plane character in the rendered prompt would produce.
        return utf8ToByteArray(env, out.dump());
    })
}
