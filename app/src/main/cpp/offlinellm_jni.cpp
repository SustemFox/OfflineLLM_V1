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

#ifndef OFFLINELLM_LLAMA_TAG
#define OFFLINELLM_LLAMA_TAG "unknown"
#endif

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
    std::string s = std::string("CPU (llama.cpp ") + OFFLINELLM_LLAMA_TAG + " NEON)";
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_createContext(
        JNIEnv * env, jclass,
        jstring jpath, jint n_ctx, jint /*n_gpu_layers*/, jint n_threads) {
    const std::string path = jstring_to_string(env, jpath);
    ALOGI("createContext path=%s n_ctx=%d threads=%d tag=%s",
          path.c_str(), (int)n_ctx, (int)n_threads, OFFLINELLM_LLAMA_TAG);

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
    ALOGI("context ready tag=%s", OFFLINELLM_LLAMA_TAG);
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

static void clear_memory(llama_context * ctx) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) {
        // data=true clears KV data + metadata (replacement for llama_kv_self_clear)
        llama_memory_clear(mem, true);
    }
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

static bool is_degenerate(const std::vector<llama_token> & gen) {
    if (gen.size() < 8) return false;
    {
        llama_token last = gen.back();
        int c = 0;
        for (int i = (int)gen.size() - 1; i >= 0 && c < 20; --i) {
            if (gen[(size_t)i] == last) c++;
            else break;
        }
        if (c >= 12) return true;
    }
    for (int cycle = 1; cycle <= 12; ++cycle) {
        if ((int)gen.size() < cycle * 4) continue;
        bool ok = true;
        for (int r = 0; r < 4 && ok; ++r) {
            for (int j = 0; j < cycle; ++j) {
                size_t a = gen.size() - 1 - (size_t)j - (size_t)r * (size_t)cycle;
                size_t b = gen.size() - 1 - (size_t)j;
                if (gen[a] != gen[b]) { ok = false; break; }
            }
        }
        if (ok) return true;
    }
    return false;
}

static bool text_has_phrase_loop(const std::string & out) {
    if (out.size() < 80) return false;
    const size_t n = out.size();
    const size_t win = std::min<size_t>(60, n / 3);
    if (win < 20) return false;
    std::string tail = out.substr(n - win);
    std::string region = out.substr(n > 300 ? n - 300 : 0);
    size_t pos = 0;
    int hits = 0;
    while ((pos = region.find(tail, pos)) != std::string::npos) {
        hits++;
        pos += win / 2;
        if (hits >= 3) return true;
    }
    return false;
}

static llama_sampler * make_sampler(
        float temperature,
        float top_p,
        float repeat_penalty,
        float frequency_penalty) {
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (!chain) return nullptr;

    // penalties: last_n=-1 (ctx), repeat, freq, present=0
    const float rep = repeat_penalty > 0.f ? repeat_penalty : 1.0f;
    const float freq = frequency_penalty >= 0.f ? frequency_penalty : 0.f;
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(-1, rep, freq, 0.0f));

    // top-k then top-p then temp then dist
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    const float tp = (top_p > 0.f && top_p <= 1.f) ? top_p : 0.9f;
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(tp, 1));

    const float temp = temperature > 0.01f ? temperature : 0.01f;
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return chain;
}

static std::string generate_loop(
        ContextHandle * handle,
        const std::string & user,
        const std::string & system,
        int max_tokens,
        float temperature,
        float top_p,
        float repeat_penalty,
        float frequency_penalty,
        JNIEnv * env,
        jobject callback) {
    std::lock_guard<std::mutex> lock(handle->mu);
    if (!handle->ctx || !handle->model || !handle->vocab) {
        return "ERROR: null context";
    }

    clear_memory(handle->ctx);

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

    // Evaluate prompt (batch_get_one is still 2-arg on b10079)
    for (int i = 0; i < n_tok; ++i) {
        llama_batch batch = llama_batch_get_one(&tokens[(size_t)i], 1);
        if (llama_decode(handle->ctx, batch) != 0) {
            ALOGE("prompt decode fail at %d", i);
            return "ERROR: prompt decode failed";
        }
    }

    llama_sampler * smpl = make_sampler(temperature, top_p, repeat_penalty, frequency_penalty);
    if (!smpl) {
        return "ERROR: sampler init failed";
    }

    jmethodID onToken = nullptr;
    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string out;
    std::vector<llama_token> gen;
    const int max_new = max_tokens > 0 ? max_tokens : 128;
    int n_cur = n_tok;

    for (int step = 0; step < max_new; ++step) {
        if (n_cur >= n_ctx - 2) break;

        llama_token id = llama_sampler_sample(smpl, handle->ctx, -1);
        llama_sampler_accept(smpl, id);

        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        gen.push_back(id);
        if (is_degenerate(gen)) {
            ALOGI("stop: degenerate token loop at step %d", step);
            break;
        }

        std::string piece = token_to_piece_str(vocab, id);
        if (!piece.empty()) {
            out += piece;
            if (text_has_phrase_loop(out)) {
                ALOGI("stop: phrase loop");
                break;
            }
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

    llama_sampler_free(smpl);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_runInference(
        JNIEnv * env, jclass,
        jlong ptr, jstring jprompt, jstring jsystem,
        jint maxTokens, jfloat temperature, jfloat topP,
        jfloat repeatPenalty, jfloat frequencyPenalty) {
    if (!ptr) return env->NewStringUTF("ERROR: null context");
    auto * handle = reinterpret_cast<ContextHandle *>(ptr);
    std::string out = generate_loop(
        handle,
        jstring_to_string(env, jprompt),
        jstring_to_string(env, jsystem),
        (int)maxTokens,
        (float)temperature,
        (float)topP,
        (float)repeatPenalty,
        (float)frequencyPenalty,
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
        jfloat repeatPenalty, jfloat frequencyPenalty,
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
        (float)repeatPenalty,
        (float)frequencyPenalty,
        env,
        callback
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_benchmark(
        JNIEnv * env, jclass, jlong, jint, jint) {
    std::string s = std::string("benchmark: n/a (CPU JNI ") + OFFLINELLM_LLAMA_TAG + ")";
    return env->NewStringUTF(s.c_str());
}
