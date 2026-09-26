// JNI bridge to llama.cpp. Deliberately small: load a GGUF, complete one prompt (optionally
// under a GBNF grammar), free. Prompt formatting and parsing live in Kotlin, in :core.
// Filter Logcat by the tag "PardaLLM" to see model load and timing logs.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "PardaLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int BATCH = 512;

struct Session {
    llama_model   * model;
    llama_context * ctx;
};

void log_to_logcat(ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

std::string to_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

int thread_count() {
    const long cores = sysconf(_SC_NPROCESSORS_ONLN);
    return (int) std::clamp(cores - 2, 2L, 6L);
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_parda_llm_LlamaNative_init(JNIEnv * env, jobject, jstring native_lib_dir) {
    llama_log_set(log_to_logcat, nullptr);
    ggml_backend_load_all_from_path(to_string(env, native_lib_dir).c_str());
    llama_backend_init();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_parda_llm_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx) {
    const std::string path = to_string(env, jpath);
    llama_model * model = llama_model_load_from_file(path.c_str(), llama_model_default_params());
    if (!model) {
        LOGE("could not load model %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = n_ctx;
    cp.n_batch         = BATCH;
    cp.n_ubatch        = BATCH;
    cp.n_threads       = thread_count();
    cp.n_threads_batch = thread_count();
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("could not create context");
        llama_model_free(model);
        return 0;
    }
    LOGI("loaded %s, n_ctx=%d, threads=%d", path.c_str(), n_ctx, cp.n_threads);
    return reinterpret_cast<jlong>(new Session{model, ctx});
}

/** Returns UTF-8 bytes (not a jstring: NewStringUTF chokes on 4-byte characters). */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_parda_llm_LlamaNative_complete(JNIEnv * env, jobject, jlong handle, jstring jprompt,
                                        jstring jgrammar, jint max_tokens) {
    auto * s = reinterpret_cast<Session *>(handle);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt = to_string(env, jprompt);
    const std::string grammar = to_string(env, jgrammar);
    const int64_t t0 = ggml_time_us();

    // Every call starts from an empty context: no state carries between requests.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true);
    if (n_prompt + max_tokens > (int) llama_n_ctx(s->ctx)) {
        LOGE("prompt too long: %d tokens", n_prompt);
        return to_bytes(env, "");
    }
    for (int i = 0; i < n_prompt; i += BATCH) {
        const int n = std::min(BATCH, n_prompt - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            LOGE("prompt decode failed");
            return to_bytes(env, "");
        }
    }
    const int64_t t_prompt = ggml_time_us();

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        // Constrained: the grammar masks every token that would leave the language, then greedy.
        llama_sampler * g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) {
            LOGE("grammar failed to parse");
            llama_sampler_free(smpl);
            return to_bytes(env, "");
        }
        llama_sampler_chain_add(smpl, g);
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    int n_gen = 0;
    char buf[256];
    for (; n_gen < max_tokens; n_gen++) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        const int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGE("decode failed during generation");
            break;
        }
    }
    llama_sampler_free(smpl);

    const int64_t t_end = ggml_time_us();
    LOGI("prompt %d tok in %lld ms, generated %d tok in %lld ms%s", n_prompt,
         (long long) (t_prompt - t0) / 1000, n_gen, (long long) (t_end - t_prompt) / 1000,
         grammar.empty() ? "" : " (grammar)");
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_app_parda_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}
