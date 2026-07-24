#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <csetjmp>
#include <csignal>

#include "llama.h"

#define LOG_TAG "OfflineLLM_JNI"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef OFFLINELLM_LLAMA_TAG
#define OFFLINELLM_LLAMA_TAG "unknown"
#endif

#ifndef OFFLINELLM_OPENCL_BUILT
#define OFFLINELLM_OPENCL_BUILT 0
#endif

#ifndef OFFLINELLM_VULKAN_BUILT
#define OFFLINELLM_VULKAN_BUILT 0
#endif

#if OFFLINELLM_OPENCL_BUILT || OFFLINELLM_VULKAN_BUILT
#define OFFLINELLM_GPU_OFFLOAD_BUILT 1
#else
#define OFFLINELLM_GPU_OFFLOAD_BUILT 0
#endif

static std::once_flag g_backend_once;

struct ContextHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_gpu_layers = 0;
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
    std::string s = "CPU";
#if OFFLINELLM_OPENCL_BUILT && OFFLINELLM_VULKAN_BUILT
    s = "CPU+OpenCL+Vulkan";
#elif OFFLINELLM_VULKAN_BUILT
    s = "CPU+Vulkan";
#elif OFFLINELLM_OPENCL_BUILT
    s = "CPU+OpenCL";
#endif
    s += " (llama.cpp ";
    s += OFFLINELLM_LLAMA_TAG;
    s += ")";
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_isOpenClBuilt(JNIEnv *, jclass) {
#if OFFLINELLM_OPENCL_BUILT
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_isVulkanBuilt(JNIEnv *, jclass) {
#if OFFLINELLM_VULKAN_BUILT
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Catch native SIGSEGV during GPU backend init (e.g. Adreno + ggml-vulkan CreateFence NPE).
// Kotlin try/catch cannot recover process-killing signals.
static thread_local sigjmp_buf g_load_jmp;
static thread_local volatile sig_atomic_t g_load_guard = 0;

static void offlinellm_load_sig_handler(int sig) {
    if (g_load_guard) {
        siglongjmp(g_load_jmp, sig ? sig : 1);
    }
    // Not in guarded region — restore default and re-raise
    signal(sig, SIG_DFL);
    raise(sig);
}

struct ScopedLoadSignalGuard {
    struct sigaction old_segv {};
    struct sigaction old_bus {};
    bool active = false;
    ScopedLoadSignalGuard() {
        struct sigaction sa {};
        sa.sa_handler = offlinellm_load_sig_handler;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        if (sigaction(SIGSEGV, &sa, &old_segv) != 0) return;
        if (sigaction(SIGBUS, &sa, &old_bus) != 0) {
            sigaction(SIGSEGV, &old_segv, nullptr);
            return;
        }
        active = true;
        g_load_guard = 1;
    }
    ~ScopedLoadSignalGuard() {
        if (!active) return;
        g_load_guard = 0;
        sigaction(SIGSEGV, &old_segv, nullptr);
        sigaction(SIGBUS, &old_bus, nullptr);
    }
};

static ContextHandle * try_create_unguarded(const std::string & path, int n_ctx, int n_gpu_layers, int n_threads) {
    llama_model_params mparams = llama_model_default_params();
#if OFFLINELLM_GPU_OFFLOAD_BUILT
    mparams.n_gpu_layers = n_gpu_layers;
#else
    (void)n_gpu_layers;
    mparams.n_gpu_layers = 0;
#endif

    ALOGI("load model path=%s n_gpu_layers=%d opencl_built=%d vulkan_built=%d",
          path.c_str(), (int)mparams.n_gpu_layers, OFFLINELLM_OPENCL_BUILT, OFFLINELLM_VULKAN_BUILT);

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        ALOGE("model load failed: %s (ngl=%d)", path.c_str(), (int)mparams.n_gpu_layers);
        return nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 2048;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        ALOGE("context create failed");
        llama_model_free(model);
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        ALOGE("null vocab");
        llama_free(ctx);
        llama_model_free(model);
        return nullptr;
    }

    auto * handle = new ContextHandle();
    handle->model = model;
    handle->ctx = ctx;
    handle->vocab = vocab;
    handle->n_gpu_layers = (int)mparams.n_gpu_layers;
    ALOGI("context ready tag=%s ngl=%d", OFFLINELLM_LLAMA_TAG, handle->n_gpu_layers);
    return handle;
}

/** try_create with optional SEGV/BUS catch when GPU offload is requested. */
static ContextHandle * try_create(const std::string & path, int n_ctx, int n_gpu_layers, int n_threads) {
#if OFFLINELLM_GPU_OFFLOAD_BUILT
    if (n_gpu_layers != 0) {
        ScopedLoadSignalGuard guard;
        if (guard.active) {
            int jumped = sigsetjmp(g_load_jmp, 1);
            if (jumped != 0) {
                ALOGE("native signal %d during GPU model load (ngl=%d) — aborting this attempt",
                      jumped, n_gpu_layers);
                return nullptr;
            }
            return try_create_unguarded(path, n_ctx, n_gpu_layers, n_threads);
        }
    }
#endif
    return try_create_unguarded(path, n_ctx, n_gpu_layers, n_threads);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_createContext(
        JNIEnv * env, jclass,
        jstring jpath, jint n_ctx, jint n_gpu_layers, jint n_threads) {
    const std::string path = jstring_to_string(env, jpath);
    ALOGI("createContext path=%s n_ctx=%d ngl=%d threads=%d tag=%s opencl=%d vulkan=%d",
          path.c_str(), (int)n_ctx, (int)n_gpu_layers, (int)n_threads,
          OFFLINELLM_LLAMA_TAG, OFFLINELLM_OPENCL_BUILT, OFFLINELLM_VULKAN_BUILT);

    std::call_once(g_backend_once, []() {
        ALOGI("llama_backend_init (opencl=%d vulkan=%d)", OFFLINELLM_OPENCL_BUILT, OFFLINELLM_VULKAN_BUILT);
        llama_backend_init();
        ALOGI("llama_backend_init done");
    });

    int want_ngl = (int)n_gpu_layers;
#if !OFFLINELLM_GPU_OFFLOAD_BUILT
    want_ngl = 0;
#endif

    ContextHandle * handle = try_create(path, (int)n_ctx, want_ngl, (int)n_threads);
#if OFFLINELLM_GPU_OFFLOAD_BUILT
    if (!handle && want_ngl != 0) {
        ALOGW("GPU offload load failed/crashed — falling back to CPU n_gpu_layers=0");
        handle = try_create(path, (int)n_ctx, 0, (int)n_threads);
    }
#endif
    if (!handle) {
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_releaseContext(JNIEnv *, jclass, jlong ptr) {
    if (!ptr) return;
    auto * handle = reinterpret_cast<ContextHandle *>(ptr);
    llama_context * ctx = nullptr;
    llama_model * model = nullptr;
    {
        // Never destroy the mutex while holding lock_guard on it
        std::lock_guard<std::mutex> lock(handle->mu);
        ctx = handle->ctx;
        model = handle->model;
        handle->ctx = nullptr;
        handle->model = nullptr;
        handle->vocab = nullptr;
    }
    if (ctx) {
        llama_free(ctx);
    }
    if (model) {
        llama_model_free(model);
    }
    delete handle;
}

/** ChatML for Qwen2.5 / Qwen3 / Qwen3.5 — plain User/Assistant loops on tiny models. */
static std::string build_prompt(const std::string & system, const std::string & user) {
    std::string sys = system;
    if (sys.empty()) {
        sys = "You are a helpful offline assistant on a phone. Answer briefly in the user's language. "
              "Do not repeat the same paragraph. Do not use XML tags.";
    }
    // Qwen3 / 3.5 default to "thinking" mode; keep answers short on-device
    if (sys.find("/no_think") == std::string::npos &&
        sys.find("/think") == std::string::npos) {
        sys += "\n/no_think";
    }
    std::string p;
    p.reserve(sys.size() + user.size() + 160);
    p += "<|im_start|>system\n";
    p += sys;
    p += "<|im_end|>\n";
    p += "<|im_start|>user\n";
    p += user;
    p += "<|im_end|>\n";
    p += "<|im_start|>assistant\n";
    return p;
}

static void clear_memory(llama_context * ctx) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) {
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

static std::string trim_copy(const std::string & s) {
    size_t a = 0;
    while (a < s.size() && (s[a] == ' ' || s[a] == '\n' || s[a] == '\r' || s[a] == '\t')) a++;
    size_t b = s.size();
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\n' || s[b - 1] == '\r' || s[b - 1] == '\t')) b--;
    return s.substr(a, b - a);
}

/** Collapse consecutive duplicate paragraphs / long repeated chunks. */
static std::string collapse_repeats(const std::string & in) {
    if (in.size() < 40) return in;

    // Split on blank lines into paragraphs
    std::vector<std::string> paras;
    size_t i = 0;
    while (i < in.size()) {
        while (i < in.size() && (in[i] == '\n' || in[i] == '\r')) i++;
        if (i >= in.size()) break;
        size_t j = i;
        while (j < in.size()) {
            if (in[j] == '\n' && j + 1 < in.size() && in[j + 1] == '\n') break;
            if (in[j] == '\n' && j + 1 < in.size() && in[j + 1] == '\r' && j + 2 < in.size() && in[j + 2] == '\n') break;
            j++;
        }
        std::string p = trim_copy(in.substr(i, j - i));
        if (!p.empty()) paras.push_back(p);
        i = j;
        while (i < in.size() && (in[i] == '\n' || in[i] == '\r')) i++;
    }
    if (paras.empty()) return in;

    std::vector<std::string> outp;
    for (const auto & p : paras) {
        if (!outp.empty() && outp.back() == p) {
            continue; // skip consecutive dup paragraph
        }
        // also skip if same as any of last 2 (A B A B pattern → drop second A/B cycle start)
        if (outp.size() >= 2 && outp[outp.size() - 2] == p) {
            continue;
        }
        outp.push_back(p);
    }

    // If still many paras but only 2 unique alternating — keep first two unique once
    if (outp.size() > 4) {
        // detect full-output cycle: first half == second half roughly
        std::string joined;
        for (size_t k = 0; k < outp.size(); ++k) {
            if (k) joined += "\n\n";
            joined += outp[k];
        }
        // fallback token: if more than 3 paras and para0==para2 and para1==para3
        if (outp.size() >= 4 && outp[0] == outp[2] && outp[1] == outp[3]) {
            return outp[0] + "\n\n" + outp[1];
        }
        return joined;
    }

    std::string joined;
    for (size_t k = 0; k < outp.size(); ++k) {
        if (k) joined += "\n\n";
        joined += outp[k];
    }
    return joined.empty() ? in : joined;
}

static bool is_degenerate(const std::vector<llama_token> & gen) {
    if (gen.size() < 6) return false;
    {
        llama_token last = gen.back();
        int c = 0;
        for (int i = (int)gen.size() - 1; i >= 0 && c < 16; --i) {
            if (gen[(size_t)i] == last) c++;
            else break;
        }
        if (c >= 8) return true;
    }
    for (int cycle = 1; cycle <= 24; ++cycle) {
        if ((int)gen.size() < cycle * 3) continue;
        bool ok = true;
        for (int r = 0; r < 3 && ok; ++r) {
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

/** True if the newest paragraph already appeared earlier (screenshot-style loop). */
static bool text_has_paragraph_loop(const std::string & out) {
    if (out.size() < 50) return false;
    // last paragraph
    size_t end = out.size();
    while (end > 0 && (out[end - 1] == '\n' || out[end - 1] == ' ')) end--;
    if (end < 20) return false;
    size_t start = end;
    // walk back to previous blank line
    while (start > 0) {
        if (out[start - 1] == '\n' && start >= 2 && out[start - 2] == '\n') break;
        start--;
    }
    while (start < end && (out[start] == '\n' || out[start] == ' ')) start++;
    if (end <= start) return false;
    std::string last = out.substr(start, end - start);
    if (last.size() < 24) {
        // short last line — use longer tail window
        size_t win = std::min<size_t>(80, out.size() / 2);
        if (win < 30) return false;
        last = out.substr(out.size() - win);
        start = out.size() - win;
    }
    // count occurrences in prefix
    std::string prefix = out.substr(0, start);
    if (prefix.size() < last.size()) return false;
    size_t pos = 0;
    int hits = 0;
    while ((pos = prefix.find(last, pos)) != std::string::npos) {
        hits++;
        pos += std::max<size_t>(1, last.size() / 2);
        if (hits >= 1) {
            // even one prior full paragraph match = loop starting
            return true;
        }
    }
    // also: long tail repeated (non-paragraph)
    if (out.size() >= 120) {
        size_t win = std::min<size_t>(100, out.size() / 3);
        std::string tail = out.substr(out.size() - win);
        std::string region = out.substr(0, out.size() - win);
        pos = 0;
        hits = 0;
        while ((pos = region.find(tail, pos)) != std::string::npos) {
            hits++;
            pos += win / 2;
            if (hits >= 1) return true;
        }
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

    // Stronger anti-repeat for tiny models (0.5B)
    const float rep = repeat_penalty >= 1.0f ? repeat_penalty : 1.2f;
    const float freq = frequency_penalty >= 0.f ? frequency_penalty : 0.25f;
    const float present = 0.15f;
    // penalty_last_n: 256 tokens of history
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(256, rep, freq, present));

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(30));
    const float tp = (top_p > 0.f && top_p <= 1.f) ? top_p : 0.85f;
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(tp, 1));

    if (temperature <= 0.01f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        // slightly cooler default path if caller passes high temp
        float t = temperature;
        if (t > 0.9f) t = 0.9f;
        llama_sampler_chain_add(chain, llama_sampler_init_temp(t));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return chain;
}

/** Only true stop tokens — NOT <think> (Qwen3/3.5 often starts with think tags). */
static bool looks_like_im_end(const std::string & out) {
    return out.find("<|im_end|>") != std::string::npos
        || out.find("<|endoftext|>") != std::string::npos
        || out.find("<|im_start|>") != std::string::npos
        || out.find("<|end|>") != std::string::npos;
}

/** Remove chat special tokens and think/reasoning markup from visible text. */
static std::string strip_special(const std::string & in) {
    std::string s = in;
    const char * tags[] = {
        "<|im_end|>", "<|im_start|>", "<|endoftext|>",
        "<|end|>", "</s>", "<s>",
        "<|redacted_thinking|>", "</|redacted_thinking|>",
        nullptr
    };
    for (int t = 0; tags[t]; ++t) {
        size_t pos;
        while ((pos = s.find(tags[t])) != std::string::npos) {
            s.erase(pos, std::strlen(tags[t]));
        }
    }
    // Drop well-formed <think>...</think> (and variants) entirely
    const char * opens[] = { "<think>", "<thinking>", "<reasoning>", "<thought>", nullptr };
    const char * closes[] = { "</think>", "</thinking>", "</reasoning>", "</thought>", nullptr };
    for (int t = 0; opens[t]; ++t) {
        for (;;) {
            size_t a = s.find(opens[t]);
            if (a == std::string::npos) break;
            size_t b = s.find(closes[t], a + std::strlen(opens[t]));
            if (b == std::string::npos) {
                // unclosed: drop open tag only, keep rest (may still be streaming answer after)
                s.erase(a, std::strlen(opens[t]));
                break;
            }
            s.erase(a, (b + std::strlen(closes[t])) - a);
        }
    }
    // orphan close tags
    for (int t = 0; closes[t]; ++t) {
        size_t pos;
        while ((pos = s.find(closes[t])) != std::string::npos) {
            s.erase(pos, std::strlen(closes[t]));
        }
    }
    return trim_copy(s);
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
    // Cap default lower — long gens on 0.5B almost always loop
    int max_new = max_tokens > 0 ? max_tokens : 128;
    if (max_new > 512) max_new = 512;
    int n_cur = n_tok;
    bool stopped_loop = false;

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
            stopped_loop = true;
            break;
        }

        std::string piece = token_to_piece_str(vocab, id);
        if (!piece.empty()) {
            out += piece;

            if (looks_like_im_end(out)) {
                ALOGI("stop: special end tag");
                out = strip_special(out);
                break;
            }

            if (text_has_paragraph_loop(out)) {
                ALOGI("stop: paragraph/phrase loop at step %d len=%zu", step, out.size());
                stopped_loop = true;
                break;
            }

            if (callback && onToken) {
                // Stream collapsed view so UI doesn't flash 3x loops mid-way
                std::string view = collapse_repeats(strip_special(out));
                jstring js = env->NewStringUTF(view.c_str());
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

    const size_t raw_len = out.size();
    out = strip_special(out);
    out = collapse_repeats(out);
    ALOGI("gen done raw_len=%zu clean_len=%zu stopped_loop=%d preview=%.80s",
          raw_len, out.size(), stopped_loop ? 1 : 0, out.c_str());
    if (stopped_loop) {
        ALOGI("final after loop-collapse len=%zu", out.size());
    }

    // Always push final frame (even empty) so Kotlin can detect finished stream
    if (callback && onToken) {
        jstring js = env->NewStringUTF(out.c_str());
        env->CallVoidMethod(callback, onToken, js);
        env->DeleteLocalRef(js);
    }

    if (out.empty() && raw_len > 0) {
        ALOGW("all tokens stripped (likely think-only). raw_len=%zu", raw_len);
        // Fallback: return a short note so UI is not a blank bubble
        out = "(модель ответила только блоком мышления — попробуй /no_think в system prompt или max tokens ≥ 64)";
        if (callback && onToken) {
            jstring js = env->NewStringUTF(out.c_str());
            env->CallVoidMethod(callback, onToken, js);
            env->DeleteLocalRef(js);
        }
    }

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
    std::string s = std::string("benchmark: n/a (") +
#if OFFLINELLM_OPENCL_BUILT && OFFLINELLM_VULKAN_BUILT
        "CPU+OpenCL+Vulkan JNI "
#elif OFFLINELLM_VULKAN_BUILT
        "CPU+Vulkan JNI "
#elif OFFLINELLM_OPENCL_BUILT
        "CPU+OpenCL JNI "
#else
        "CPU JNI "
#endif
        + OFFLINELLM_LLAMA_TAG + ")";
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_offlinellm_llama_LlamaBridge_getLoadedGpuLayers(JNIEnv *, jclass, jlong ptr) {
    if (!ptr) return 0;
    auto * handle = reinterpret_cast<ContextHandle *>(ptr);
    return handle->n_gpu_layers;
}
