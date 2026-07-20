# OfflineLLM_V1 — Статус

> Текущая ветка: `main`
> Статус: ✅ Готов к сборке в CI

## Что сделано

### Движок (llama.cpp + аппаратное ускорение)
- ✅ JNI-мост в `LlamaBridge.kt`
- ✅ Автовыбор бэкенда: Vulkan GPU → OpenCL GPU → CPU
- ✅ Prebuilt .so для arm64-v8a (8 файлов, 27MB)
- ✅ Потоковая генерация через Kotlin Flow

### HTTP-сервер (хостинг)
- ✅ OpenAI-compatible API (`/v1/chat/completions`)
- ✅ Ktor + Netty, порт 8080
- ✅ Подключение Kai / OpenClaw по http://телефон:8080/v1

### Модели
- ✅ Сканер GGUF на устройстве
- ✅ Загрузка с HuggingFace
- ✅ Рекомендованные модели (Qwen 2.5, Llama 3.2)

### UI
- ✅ Compose-чаты с Flow-стримингом
- ✅ Индикатор бэкенда (NPU/GPU/CPU)
- ✅ Управление моделями
- ✅ Вкл/выкл HTTP-сервера

### CI/CD
- ✅ GitHub Actions собирает APK при пуше в main
- ✅ APK доступен как артефакт сборки
- ✅ Ручной запуск через workflow_dispatch (debug/release)

## Требуется
1. Скачать APK из Actions и установить на OnePlus 7
2. Скачать GGUF-модель (например, Qwen 2.5 1.5B Q4 — 930MB)
3. Положить в `Android/data/com.example.offlinellm/files/models/`
4. Запустить, выбрать модель, общаться
5. В настройках включить HTTP-сервер → подключить Kai

## Производительность (ожидаемая, OnePlus 7)
| Модель | Бэкенд  | t/s |
|--------|---------|-----|
| 1.5B Q4 | Vulkan  | ~15-25 |
| 3B Q4   | Vulkan  | ~8-12 |
| 7B Q4   | Vulkan  | ~4-7 |
