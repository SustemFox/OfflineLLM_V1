# OfflineLLM_V1 — Статус

> Текущая ветка: `main` @ `36514947`
> Статус: ✅ CI green — release APK artifact **OfflineLLM-v1.0** (~12 MB zip)

## Что сделано

### Движок (llama.cpp + аппаратное ускорение)
- ✅ JNI-мост в `LlamaBridge.kt`
- ✅ Автовыбор бэкенда: Vulkan GPU → OpenCL GPU → CPU
- ✅ Prebuilt .so для arm64-v8a
- ✅ Потоковая генерация через Kotlin Flow

### HTTP-сервер (хостинг)
- ✅ OpenAI-compatible API (`/v1/chat/completions`, `/v1/models`)
- ✅ Ktor + Netty, порт 8080
- ✅ `generate` callback из ChatViewModel → llmRepository

### Модели / скачивание (2026-07-22)
- ✅ Progress + cancel + `.gguf.part`
- ✅ HF redirects, User-Agent, Accept-Encoding: identity
- ✅ `downloadingModelId` для корректного UI на карточке
- ✅ Indeterminate progress при unknown Content-Length
- ✅ quantType / parameterCount в списке
- ✅ deleteModel удаляет файл
- ✅ «Выбрать» грузит модель в real llama.cpp

### UI (2026-07-22)
- ✅ Settings LazyColumn (CI compile fix)
- ✅ TopAppBar назад, SAF folder picker, theme toggle
- ✅ Download banner в chat + settings
- ✅ System message styling

### CI/CD
- ✅ [Build 29860917812](https://github.com/SustemFox/OfflineLLM_V1/actions/runs/29860917812) success
- ✅ Artifact: OfflineLLM-v1.0

## Как пользоваться
1. Actions → последний green run → скачать **OfflineLLM-v1.0**
2. Установить APK на arm64 устройство
3. ⚙ → скачать модель (лучше Qwen 2.5 1.5B для OnePlus 7)
4. «Выбрать» → дождаться загрузки в движок
5. Чат; опционально HTTP-сервер для Kai
