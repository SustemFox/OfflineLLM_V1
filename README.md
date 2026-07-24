# Offline LLM

Android app for running GGUF language models **on-device** (llama.cpp), with optional local OpenAI-compatible HTTP server.

## Features

- Download GGUF models (catalog + Hugging Face search)
- Chat UI (Compose)
- llama.cpp native engine (CPU, experimental OpenCL + Vulkan)
- SAF storage for models
- Local HTTP API (LAN IP + configurable port)
- LLM settings: temperature, top-p, penalties, context, threads, system prompt

## Requirements

- Android device (arm64 recommended)
- Enough free storage for GGUF weights (small Q4 models ~0.4–1 GB+)

## Build

```bash
./gradlew :app:assembleDebug
```

CI builds an APK artifact on push to `main` (see `.github/workflows/android.yml`).

## Notes

- Large models need RAM; start with small Q4_K_M builds (e.g. Qwen 0.6B–1.7B / 3.5 0.8B).
- OpenCL / Vulkan offload is experimental and device-dependent (often CPU fallback on older Adreno).
- Hugging Face token is optional (rate limits / gated repos); it does not usually increase CDN speed.

## License

See repository files / upstream llama.cpp licensing for native components.
