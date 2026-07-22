# OfflineLLM_V1 — Статус

> v1.3.0-llm — server IP/port, LLM settings, thinking UI, anti-repetition

## v1.3
- HTTP: shows real LAN IP(s) + editable port (1024–65535), restart on apply
- LLM settings: temperature, top_p, max tokens, n_ctx, threads, system prompt, show thinking, repeat/freq penalty
- Chat: collapsible **Мышление** from `<think>` / `<thinking>` tags
- JNI: repeat penalty + frequency penalty + loop/degenerate stop + top-k

## CI tip
Artifact OfflineLLM-v1.0 ; rebuild via empty commit OK.
