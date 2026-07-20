# OfflineLLM_V1 — План проекта

## Цель
Создать Android-приложение для локального (оффлайн) использования LLM с аппаратным ускорением.
Телефон выступает одновременно как чат-интерфейс и как хост для OpenAI-совместимого API.

## Архитектура

```
com.example.offlinellm
├── data
│   ├── repository
│   │   ├── FakeLlmRepository.kt        ← имитация для разработки UI
│   │   ├── FakeModelRepository.kt      ← имитация загрузки
│   │   ├── LocalLlmRepository.kt       ← ✅ реальный llama.cpp через JNI
│   │   └── ModelRepositoryImpl.kt      ← ✅ реальный менеджер моделей (GGUF)
│   └── service
│       └── LlmHttpServer.kt            ← ✅ HTTP-сервер (Ktor + OpenAI API)
├── di
│   └── AppProvider.kt                   ← ✅ DI с переключением fake/real
├── domain
│   ├── model
│   │   ├── DownloadState.kt
│   │   ├── LlmModel.kt                 ← ✅ обновлено (downloadUrl, backend, paramCount)
│   │   └── Message.kt
│   └── repository
│       ├── LlmRepository.kt
│       └── ModelRepository.kt          ← ✅ обновлён (getActiveBackend, getModelPath)
├── llama
│   ├── LlamaBridge.kt                  ← ✅ JNI интерфейс к нативному llama.cpp
│   ├── LlamaInferenceEngine.kt         ← ✅ Kotlin обёртка с Flow-стримингом
│   └── ModelLoader.kt                  ← ✅ Сканер GGUF + рекомендованные модели
└── MainActivity.kt
```

## Движок: llama.cpp с аппаратным ускорением

| Бэкенд | Технология | Устройства |
|--------|-----------|------------|
| 🥇 Hexagon NPU | Qualcomm HTP v73+ | Snapdragon (855, 8 Gen 1/2/3...) |
| 🥈 Vulkan GPU | Adreno GPU | Все Android с Vulkan |
| 🥉 OpenCL GPU | Adreno GPU (legacy) | Snapdragon 845 и старше |
| 🔄 CPU NEON | ARM NEON | Все устройства (fallback) |

## HTTP-сервер (хостинг моделей)

- OpenAI-compatible API (`/v1/chat/completions`, `/v1/models`)
- Любой клиент может подключиться (Kai, OpenClaw, curl)
- Порт 8080 по умолчанию

## Статус
См. STATUS.md
