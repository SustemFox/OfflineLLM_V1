# OfflineLLM_V1

Android offline LLM (llama.cpp) + OpenAI-compatible local HTTP API.

## Features
- On-device inference: Vulkan / OpenCL / CPU (`arm64-v8a`)
- Download GGUF models with progress + cancel
- Shared models folder (SAF / custom path)
- Chat UI (Jetpack Compose)
- Optional HTTP server on `:8080` (`/v1/models`, `/v1/chat/completions`)

## Build
```bash
./gradlew :app:assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`

Or use GitHub Actions workflow **Build OfflineLLM APK**.

## Docs
- [PLAN.md](PLAN.md) — architecture
- [STATUS.md](STATUS.md) — current status
- [TODO.md](TODO.md) — backlog (partially stale)
