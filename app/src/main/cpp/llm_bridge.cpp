// llm_bridge.cpp
//
// Minimal JNI bridge around llama.cpp for on-device Granite inference.
// Exposes: load(path, nThreads) -> handle, generate(handle, systemPrompt,
// userText, maxTokens, temperature) -> String, free(handle).
//
// One handle == one loaded GGUF model + its own llama_context. AnkiCopy
// keeps up to 3 handles alive at once (one per Granite model) so R1 can
// switch between them instantly without a reload; see LlamaEngine.kt.

#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "AnkiCopyLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LoadedModel {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

std::mutex g_mutex;
std::unordered_map<jlong, LoadedModel> g_models;
jlong g_next_handle = 1;
bool g_backend_initialized = false;

void ensure_backend() {
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
}

std::string jstring_to_std(JNIEnv *env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ankicopy_llm_LlamaEngine_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jstring jModelPath, jint nThreads, jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend();

    std::string modelPath = jstring_to_std(env, jModelPath);

    llama_model_params mparams = llama_model_default_params();
    // Granite 1b/3b/micro all fit comfortably on CPU; leave GPU offload at 0
    // since Android GPU backends for llama.cpp are inconsistent across
    // devices/OEMs. Can be raised later if Vulkan backend proves stable.
    mparams.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        LOGE("Failed to load model from %s", modelPath.c_str());
        return -1;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx > 0 ? nCtx : 2048);
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("Failed to create context for %s", modelPath.c_str());
        llama_model_free(model);
        return -1;
    }

    LoadedModel lm;
    lm.model = model;
    lm.ctx = ctx;
    lm.vocab = llama_model_get_vocab(model);

    jlong handle = g_next_handle++;
    g_models[handle] = lm;
    LOGI("Loaded model handle=%lld path=%s", (long long) handle, modelPath.c_str());
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ankicopy_llm_LlamaEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(handle);
    if (it == g_models.end()) return;
    if (it->second.ctx) llama_free(it->second.ctx);
    if (it->second.model) llama_model_free(it->second.model);
    g_models.erase(it);
    LOGI("Freed model handle=%lld", (long long) handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ankicopy_llm_LlamaEngine_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jstring jSystemPrompt, jstring jUserText,
        jint maxTokens, jfloat temperature) {

    LoadedModel lm;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_models.find(handle);
        if (it == g_models.end()) {
            return env->NewStringUTF("[error] model handle not loaded");
        }
        lm = it->second;
    }

    std::string systemPrompt = jstring_to_std(env, jSystemPrompt);
    std::string userText = jstring_to_std(env, jUserText);

    // Granite instruct models use a ChatML-style template; llama.cpp's
    // built-in chat template applier handles this from the GGUF's stored
    // template metadata, so we don't hardcode special tokens here.
    std::vector<llama_chat_message> messages;
    messages.push_back({"system", systemPrompt.c_str()});
    messages.push_back({"user", userText.c_str()});

    std::vector<char> buf(userText.size() + systemPrompt.size() + 1024);
    int32_t formatted_len = llama_chat_apply_template(
            llama_model_chat_template(lm.model, nullptr),
            messages.data(), messages.size(),
            true, buf.data(), buf.size());
    if (formatted_len < 0) {
        return env->NewStringUTF("[error] chat template formatting failed");
    }
    std::string prompt(buf.data(), formatted_len);

    // Tokenize
    int32_t n_prompt_tokens = -llama_tokenize(
            lm.vocab, prompt.c_str(), (int32_t) prompt.size(),
            nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt_tokens);
    if (llama_tokenize(lm.vocab, prompt.c_str(), (int32_t) prompt.size(),
                        tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        return env->NewStringUTF("[error] tokenization failed");
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(lm.ctx, batch) != 0) {
        return env->NewStringUTF("[error] initial decode failed (context may be full)");
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    int32_t generated = 0;
    llama_token new_token;

    while (generated < maxTokens) {
        new_token = llama_sampler_sample(sampler, lm.ctx, -1);
        if (llama_vocab_is_eog(lm.vocab, new_token)) break;

        char piece[256];
        int32_t n = llama_token_to_piece(lm.vocab, new_token, piece, sizeof(piece), 0, true);
        if (n > 0) result.append(piece, n);

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(lm.ctx, next_batch) != 0) break;
        generated++;
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ankicopy_llm_LlamaEngine_nativeResetContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    // Clears the KV cache so the next generate() starts fresh instead of
    // accumulating conversation history - each tooltip explanation is a
    // one-shot request, not a chat, so we don't want context growing
    // unbounded across many long-presses.
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(handle);
    if (it == g_models.end()) return;
    llama_memory_t mem = llama_get_memory(it->second.ctx);
    if (mem) llama_memory_clear(mem, true);
}
