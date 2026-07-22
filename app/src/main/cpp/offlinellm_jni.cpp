#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "OfflineLLM_JNI"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct ContextHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::mutex mu;
};

static std::string jstring_to_string(JNIEnv * env, jstring js) {
    if (!js) return {};
    const char * chars = env->GetStringUTFChars(js, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
}

static jstring to_jstring(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

static llama_model * load_model(const char * path, llama_model_params mparams) {
#if defined(LLAMA_API_VERSION) || 1
    // Prefer newer API name when available via macro/link
#endif
#ifdef llama_model_load_from_file
    return llama_model_load_from_file(path, mparams);
#else
    return llama_load_model_from_file(path, mparams);
#endif
}

static void free_model(llama_model * model) {
#ifdef llama_model_free
    llama_model_free(model);
#else
    llama_free_model(model);
#endif
}

static llama_context * new_context(llama_model * model, llama_context_params cparams) {
#ifdef llama_init_from_model
    return llama_init_from_model(model, cparams);
#else
    return llama_new_context_with_model(model, cparams);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_getBackendInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF("CPU (llama.cpp)");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_createContext(
        JNIEnv * env, jclass,
        jstring jpath, jint n_ctx, jint /*n_gpu_layers*/, jint n_threads) {
    const std::string path = jstring_to_string(env, jpath);
    ALOGI("createContext path=%s n_ctx=%d threads=%d", path.c_str(), (int)n_ctx, (int)n_threads);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model * model = llama_load_model_from_file(path.c_str(), mparams);
    if (!model) {
        ALOGE("model load failed: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 2048;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        ALOGE("context create failed");
        llama_free_model(model);
        return 0;
    }

    auto * handle = new ContextHandle();
    handle->model = model;
    handle->ctx = ctx;
    ALOGI("context ready");
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_releaseContext(JNIEnv *, jclass, jlong ptr) {
    if (!ptr) return;
    auto * handle = reinterpret_cast<ContextHandle *>(ptr);
    std::lock_guard<std::mutex> lock(handle->mu);
    if (handle->ctx) {
        llama_free(handle->ctx);
        handle->ctx = nullptr;
    }
    if (handle->model) {
        llama_free_model(handle->model);
        handle->model = nullptr;
    }
    delete handle;
}

static std::string build_prompt(const std::string & system, const std::string & user) {
    if (!system.empty()) {
        return system + "\n\nUser: " + user + "\nAssistant:";
    }
    return "User: " + user + "\nAssistant:";
}

static void clear_kv(llama_context * ctx) {
#ifdef llama_memory_clear
    // newer
    auto * mem = llama_get_memory(ctx);
    if (mem) llama_memory_clear(mem, true);
#else
    llama_kv_cache_clear(ctx);
#endif
}

static const llama_vocab * get_vocab(const llama_model * model) {
#ifdef llama_model_get_vocab
    return llama_model_get_vocab(model);
#else
    return nullptr;
#endif
}

static int tokenize_prompt(const llama_model * model, const llama_vocab * vocab,
                           const std::string & text, std::vector<llama_token> & out) {
    out.resize(text.size() + 32);
    int n = -1;
    if (vocab) {
        n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), out.data(), (int32_t)out.size(), true, true);
        if (n < 0) {
            out.resize((size_t)(-n));
            n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), out.data(), (int32_t)out.size(), true, true);
        }
    }
#ifndef llama_tokenize
    (void)model;
#endif
    // Fallback older signature: llama_tokenize(model, ...)
    if (n < 0) {
        // try model-based tokenize if present
#if defined(__cplusplus)
        // Some versions: llama_tokenize(const llama_model*, ...)
#endif
    }
    if (n >= 0) out.resize((size_t)n);
    return n;
}

static std::string token_to_piece_str(const llama_vocab * vocab, const llama_model * model, llama_token token) {
    char buf[256];
    int n = -1;
    if (vocab) {
        n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n < 0) {
            std::string tmp;
            tmp.resize((size_t)(-n));
            int n2 = llama_token_to_piece(vocab, token, tmp.data(), (int32_t)tmp.size(), 0, true);
            if (n2 > 0) {
                tmp.resize((size_t)n2);
                return tmp;
            }
            return {};
        }
        return std::string(buf, buf + n);
    }
    (void)model;
    return {};
}

static bool is_eog_token(const llama_vocab * vocab, const llama_model * model, llama_token id) {
    if (vocab) {
#ifdef llama_vocab_is_eog
        return llama_vocab_is_eog(vocab, id);
#elif defined(llama_token_is_eog)
        return llama_token_is_eog(vocab, id);
#endif
    }
#ifdef llama_token_eos
    return id == llama_token_eos(model);
#else
    (void)model;
    return false;
#endif
}

static int n_vocab_of(const llama_vocab * vocab, const llama_model * model) {
    if (vocab) {
#ifdef llama_vocab_n_tokens
        return llama_vocab_n_tokens(vocab);
#endif
    }
#ifdef llama_n_vocab
    return llama_n_vocab(model);
#else
    (void)model;
    return 0;
#endif
}

static llama_token sample_greedy(const float * logits, int n_vocab) {
    int best = 0;
    float best_v = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > best_v) {
            best_v = logits[i];
            best = i;
        }
    }
    return (llama_token)best;
}

static llama_token sample_temp(const float * logits, int n_vocab, float temperature, float top_p) {
    if (temperature <= 0.01f) return sample_greedy(logits, n_vocab);
    std::vector<std::pair<float, int>> probs;
    probs.reserve((size_t)n_vocab);
    float max_l = logits[0];
    for (int i = 1; i < n_vocab; ++i) if (logits[i] > max_l) max_l = logits[i];
    float sum = 0.f;
    for (int i = 0; i < n_vocab; ++i) {
        float p = expf((logits[i] - max_l) / temperature);
        probs.emplace_back(p, i);
        sum += p;
    }
    for (auto & pr : probs) pr.first /= sum;
    std::sort(probs.begin(), probs.end(), [](auto & a, auto & b) { return a.first > b.first; });
    if (top_p < 1.0f && top_p > 0.0f) {
        float cum = 0.f;
        size_t cut = 0;
        for (; cut < probs.size(); ++cut) {
            cum += probs[cut].first;
            if (cum >= top_p) break;
        }
        probs.resize(cut + 1);
        float s2 = 0.f;
        for (auto & pr : probs) s2 += pr.first;
        for (auto & pr : probs) pr.first /= s2;
    }
    float r = (float)rand() / (float)RAND_MAX;
    float cum = 0.f;
    for (auto & pr : probs) {
        cum += pr.first;
        if (r <= cum) return (llama_token)pr.second;
    }
    return (llama_token)probs.back().second;
}

static std::string generate_loop(
        ContextHandle * handle,
        const std::string & user,
        const std::string & system,
        int max_tokens,
        float temperature,
        float top_p,
        JNIEnv * env,
        jobject callback) {
    std::lock_guard<std::mutex> lock(handle->mu);
    if (!handle->ctx || !handle->model) return "ERROR: null context";

    const llama_vocab * vocab = get_vocab(handle->model);
    clear_kv(handle->ctx);

    const std::string full = build_prompt(system, user);
    const int n_ctx = llama_n_ctx(handle->ctx);
    std::vector<llama_token> tokens;
    int n_tok = tokenize_prompt(handle->model, vocab, full, tokens);
    if (n_tok < 0) {
        ALOGE("tokenize failed");
        return "ERROR: tokenize failed";
    }
    if (n_tok >= n_ctx - 8) {
        return "ERROR: prompt too long for n_ctx";
    }

    // Evaluate prompt one token at a time for max API compatibility
    for (int i = 0; i < n_tok; ++i) {
        llama_batch batch = llama_batch_get_one(&tokens[(size_t)i], 1);
        if (llama_decode(handle->ctx, batch) != 0) {
            ALOGE("prompt decode fail at %d", i);
            return "ERROR: prompt decode failed";
        }
    }

    jmethodID onToken = nullptr;
    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string out;
    const int max_new = max_tokens > 0 ? max_tokens : 128;
    int n_cur = n_tok;
    const int n_vocab = n_vocab_of(vocab, handle->model);
    if (n_vocab <= 0) return "ERROR: n_vocab";

    for (int step = 0; step < max_new; ++step) {
        if (n_cur >= n_ctx - 2) break;
        float * logits = llama_get_logits_ith(handle->ctx, -1);
        if (!logits) {
            ALOGE("no logits");
            break;
        }
        llama_token id = sample_temp(logits, n_vocab, temperature, top_p);
        if (is_eog_token(vocab, handle->model, id)) break;

        std::string piece = token_to_piece_str(vocab, handle->model, id);
        if (!piece.empty()) {
            out += piece;
            if (callback && onToken) {
                jstring js = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(callback, onToken, js);
                env->DeleteLocalRef(js);
            }
        }

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(handle->ctx, batch) != 0) {
            ALOGE("gen decode fail");
            break;
        }
        n_cur++;
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_runInference(
        JNIEnv * env, jclass,
        jlong ptr, jstring jprompt, jstring jsystem,
        jint maxTokens, jfloat temperature, jfloat topP) {
    if (!ptr) return env->NewStringUTF("ERROR: null context");
    auto * handle = reinterpret_cast<ContextHandle *>(ptr);
    std::string out = generate_loop(handle, jstring_to_string(env, jprompt), jstring_to_string(env, jsystem),
                                    (int)maxTokens, (float)temperature, (float)topP, env, nullptr);
    return to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_runInferenceStream(
        JNIEnv * env, jclass,
        jlong ptr, jstring jprompt, jstring jsystem,
        jint maxTokens, jfloat temperature, jfloat topP,
        jobject callback) {
    if (!ptr) return;
    auto * handle = reinterpret_cast<ContextHandle *>(ptr);
    (void)generate_loop(handle, jstring_to_string(env, jprompt), jstring_to_string(env, jsystem),
                        (int)maxTokens, (float)temperature, (float)topP, env, callback);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_benchmark(
        JNIEnv * env, jclass, jlong, jint, jint) {
    return env->NewStringUTF("benchmark: n/a (CPU JNI)");
}
