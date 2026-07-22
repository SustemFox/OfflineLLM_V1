# OfflineLLM_V1

Android offline LLM via **llama.cpp** (CPU JNI build) + optional OpenAI-compatible HTTP on `:8080`.

## v1.1 notes
Legacy prebuilt `jniLibs` (Vulkan/OpenCL) were **broken** (corrupt ELF / no JNI).  
CI now builds **CPU-only** `libofflinellm_jni.so` from upstream [llama.cpp](https://github.com/ggml-org/llama.cpp).

## Build (CI)
Push to `main` or run workflow **Build OfflineLLM APK**.  
Artifact: `OfflineLLM-v1.1-cpu`.

Local (needs NDK + llama.cpp clone):
```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/third_party/llama.cpp
./gradlew :app:assembleRelease
```

## Docs
- [PLAN.md](PLAN.md)
- [STATUS.md](STATUS.md)
