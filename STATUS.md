# OfflineLLM_V1 — Статус

> Ветка: `main`
> Фокус: **CPU native + JNI** (битый Vulkan prebuilt удалён)

## Проблема (2026-07-22)
- Краш на Send: `IllegalStateException: Failed to load native llama.cpp libraries`
- Root cause:
  1. `libggml-vulkan.so` corrupt (`invalid shdr offset/size`)
  2. `libggml.so` / `libllama.so` **DT_NEEDED** → vulkan (нельзя просто «не грузить»)
  3. В prebuilt `.so` **нет JNI symbols** (`Java_com_example_...`) — даже валидный load не дал бы `createContext`

## Fix
- Kotlin: безопасный `LlamaBridge.load()`, CPU defaults, ошибки на Send/Select не роняют процесс
- Удалены legacy jniLibs из packaging / CI wipe
- Добавлены `app/src/main/cpp` + CMake: сборка **llama.cpp CPU-only** + `libofflinellm_jni.so`
- CI клонирует llama.cpp и собирает APK artifact `OfflineLLM-v1.1-cpu`

## Ускорение
| Backend | Статус |
|---|---|
| CPU NEON | ✅ целевой путь v1.1 |
| Vulkan | ❌ prebuilt битый; нужна отдельная валидная пересборка |
| OpenCL | ❌ отключён (зависит от vendor libOpenCL) |
| Hexagon NPU | ❌ .so нет в репо |

## Как пользоваться
1. Actions → artifact **OfflineLLM-v1.1-cpu**
2. Установить APK
3. Скачать маленькую GGUF (Qwen 0.5B/1.5B Q4)
4. «Выбрать» → дождаться load → Send
