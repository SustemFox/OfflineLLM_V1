# OfflineLLM_V1 — Статус

> Текущая ветка: `main`
> Статус: 🔧 UI + download progress fixed (2026-07-22) — ждём зелёный CI

## Что сделано

### Движок (llama.cpp + аппаратное ускорение)
- ✅ JNI-мост в `LlamaBridge.kt`
- ✅ Автовыбор бэкенда: Vulkan GPU → OpenCL GPU → CPU
- ✅ Prebuilt .so для arm64-v8a (8 файлов, 27MB)
- ✅ Потоковая генерация через Kotlin Flow

### HTTP-сервер (хостинг)
- ✅ OpenAI-compatible API (`/v1/chat/completions`)
- ✅ Ktor + Netty, порт 8080
- ✅ `generate` callback прокинут из ChatViewModel в реальный llmRepository

### Модели / скачивание
- ✅ Сканер GGUF + recommended list (Qwen / Llama)
- ✅ Download с progress (throttled emit), cancel, `.gguf.part` temp
- ✅ HF redirects + User-Agent + Accept-Encoding: identity
- ✅ quantType / parameterCount сохраняются в LlmModel
- ✅ deleteModel реально удаляет файл
- ✅ «Выбрать» грузит модель в real llama.cpp engine

### UI
- ✅ Compose chat + settings (scrollable LazyColumn — fixed receiver bug)
- ✅ TopAppBar назад, SAF folder picker, theme toggle
- ✅ Download banner + per-card progress + indeterminate при unknown size
- ✅ In-app logger viewer

### CI/CD
- ✅ GitHub Actions собирает release APK
- 🔧 Проверить run после UI fix commit

## Как пользоваться
1. Скачать APK из Actions
2. ⚙ → Скачать модель (напр. Qwen 2.5 1.5B)
3. «Выбрать» → дождаться загрузки в движок
4. Чат / HTTP-сервер для Kai
