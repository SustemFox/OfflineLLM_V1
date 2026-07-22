# OfflineLLM_V1 — Статус

> v1.4.0-llama-b10079 — latest llama.cpp release pin, CPU-only

## Native
- llama.cpp **b10079** (ggml-org release, not prerelease)
- JNI: `llama_memory_clear` (was kv_self_clear), official `llama_sampler_chain` (penalties/top_k/top_p/temp)
- Still **CPU-only** — OpenCL/Hexagon/Vulkan not linked (OP7 APU later)

## App features (carry forward)
- HTTP IP+port, LLM settings, thinking UI, HF download FGS, history

## CI
Artifact OfflineLLM-v1.0 ; GitHub App cannot edit workflows.
