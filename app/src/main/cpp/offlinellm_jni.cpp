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
    const llama_vocab * vocab = nullptr;
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

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        ALOGE("model load failed: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 2048;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        ALOGE("context create failed");
        llama_model_free(model);
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        ALOGE("null vocab");
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }

    auto * handle = new ContextHandle();
    handle->model = model;
    handle->ctx = ctx;
    handle->vocab = vocab;
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
        llama_model_free(handle->model);
        handle->model = nullptr;
    }
    handle->vocab = nullptr;
    delete handle;
}

static std::string build_prompt(const std::string & system, const std::string & user) {
    if (!system.empty()) {
        return system + "\n\nUser: " + user + "\nAssistant:";
    }
    return "User: " + user + "\nAssistant:";
}

static void clear_kv(llama_context * ctx) {
    llama_kv_self_clear(ctx);
}

static std::string token_to_piece_str(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, (int32_t)sizeof(buf), 0, true);
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

static llama_token sample_temp(const float * logits, int n_vocab, float temperature, float top_p) {
    if (temperature <= 0.01f) {
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

    std::vector<std::pair<float, int>> probs;
    probs.reserve((size_t)n_vocab);
    float max_l = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > max_l) max_l = logits[i];
    }
    float sum = 0.f;
    for (int i = 0; i < n_vocab; ++i) {
        float p = expf((logits[i] - max_l) / temperature);
        probs.emplace_back(p, i);
        sum += p;
    }
    for (auto & pr : probs) pr.first /= sum;
    std::sort(probs.begin(), probs.end(), [](const auto & a, const auto & b) {
        return a.first > b.first;
    });
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
    if (!handle->ctx || !handle->model || !handle->vocab) {
        return "ERROR: null context";
    }

    clear_kv(handle->ctx);

    const llama_vocab * vocab = handle->vocab;
    const std::string full = build_prompt(system, user);
    const int n_ctx = (int)llama_n_ctx(handle->ctx);

    std::vector<llama_token> tokens(full.size() + 32);
    int n_tok = llama_tokenize(
        vocab,
        full.c_str(),
        (int32_t)full.size(),
        tokens.data(),
        (int32_t)tokens.size(),
        true,
        true
    );
    if (n_tok < 0) {
        tokens.resize((size_t)(-n_tok));
        n_tok = llama_tokenize(
            vocab,
            full.c_str(),
            (int32_t)full.size(),
            tokens.data(),
            (int32_t)tokens.size(),
            true,
            true
        );
    }
    if (n_tok < 0) {
        ALOGE("tokenize failed");
        return "ERROR: tokenize failed";
    }
    tokens.resize((size_t)n_tok);
    if (n_tok >= n_ctx - 8) {
        return "ERROR: prompt too long for n_ctx";
    }

    // Evaluate prompt token-by-token (batch_get_one is 2-arg on b5250)
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
    const int n_vocab = llama_vocab_n_tokens(vocab);
    if (n_vocab <= 0) {
        return "ERROR: n_vocab";
    }

    for (int step = 0; step < max_new; ++step) {
        if (n_cur >= n_ctx - 2) break;

        float * logits = llama_get_logits_ith(handle->ctx, -1);
        if (!logits) {
            ALOGE("no logits");
            break;
        }

        llama_token id = sample_temp(logits, n_vocab, temperature, top_p);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        std::string piece = token_to_piece_str(vocab, id);
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
    std::string out = generate_loop(
        handle,
        jstring_to_string(env, jprompt),
        jstring_to_string(env, jsystem),
        (int)maxTokens,
        (float)temperature,
        (float)topP,
        env,
        nullptr
    );
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
    (void)generate_loop(
        handle,
        jstring_to_string(env, jprompt),
        jstring_to_string(env, jsystem),
        (int)maxTokens,
        (float)temperature,
        (float)topP,
        env,
        callback
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_benchmark(
        JNIEnv * env, jclass, jlong, jint, jint) {
    return env->NewStringUTF("benchmark: n/a (CPU JNI)");
}
