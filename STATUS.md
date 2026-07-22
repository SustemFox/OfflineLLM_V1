# OfflineLLM_V1 — Статус

> Ветка: `main` @ `a2493c25`
> CI: ✅ [run 29888533328](https://github.com/SustemFox/OfflineLLM_V1/actions/runs/29888533328)
> Artifact: **OfflineLLM-v1.0** (~4.6 MB) — CPU llama.cpp JNI

## Что было сломано (crash on Send)
1. Prebuilt `libggml-vulkan.so` — corrupt ELF (`invalid shdr offset/size`)
2. `libggml.so` / `libllama.so` **DT_NEEDED** → vulkan (нельзя «просто не грузить»)
3. В prebuilt `.so` **не было JNI** (`Java_com_example_...`) — `createContext` никогда не мог работать

## Fix (v1.1 CPU path)
- Удалены legacy `jniLibs/arm64-v8a/*.so`
- Добавлены `app/src/main/cpp` + CMake: **llama.cpp b5250 CPU-only** → `libofflinellm_jni.so`
- Gradle `cloneLlamaCpp` (workflow править App не может — 403)
- Kotlin: безопасный `LlamaBridge.load()`, `nGpuLayers=0`, catch на Select/Send, `ensureReady` при выборе модели
- Backend label: `CPU (llama.cpp)`

## Ускорение
| Backend | Статус |
|---|---|
| CPU | ✅ рабочий путь |
| Vulkan / OpenCL / Hexagon | ❌ не в этой сборке (нужны валидные prebuilts + отдельный build) |

## Как поставить
1. GitHub → Actions → run **29888533328** → artifact **OfflineLLM-v1.0**
2. Установить APK (debug-signed release)
3. Скачать небольшую GGUF (Qwen 0.5B/1.5B Q4)
4. «Выбрать» → дождаться load → Send

## Если native всё ещё fail
В чате будет system message с текстом ошибки (без process crash), UI останется в demo/fake.
