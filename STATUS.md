# OfflineLLM_V1 — Статус

> Ветка: `main`
> Фокус: **v1.2** — фон скачивания, история, prefs, HF URL

## v1.2
- Переключатель логов **сохраняется** (`AppPreferences` + `AppLogger.setEnabled`)
- Панель логов (развёрнута/свёрнута) тоже в prefs
- Тема dark/light сохраняется
- **История чата** в `filesDir/chat_history.json`
- **ForegroundService** `ModelDownloadService` — скачивание при свёрнутом приложении (уведомление + wake/wifi lock)
- **Hugging Face**: поле URL + optional token (gated models), resume через HTTP Range
- Ускорители: UI preference auto/cpu/vulkan; runtime пока **CPU JNI** (Vulkan prebuilts были битые; полный GPU build — follow-up)
- Рекомендованные модели: добавлен Qwen 0.5B

## CI
Artifact name остаётся `OfflineLLM-v1.0` (workflow App не редактирует). versionName APK: `1.2.0-bg`.
