# OfflineLLM_V1 — TODO

> Текущая ветка: `feature/modular-architecture`

## Этап 1: Рефактор прототипа
- [x] Создать `domain/model/Message.kt`
- [x] Создать `domain/model/LlmModel.kt`
- [x] Создать `domain/model/DownloadState.kt`
- [x] Создать `domain/repository/LlmRepository.kt`
- [x] Создать `domain/repository/ModelRepository.kt`
- [x] Создать `data/repository/FakeLlmRepository.kt`
- [x] Создать `data/repository/FakeModelRepository.kt`
- [x] Создать `data/repository/LocalLlmRepository.kt`
- [x] Создать `di/AppProvider.kt`
- [x] Создать `ui/chat/ChatViewModel.kt`
- [x] Создать `ui/theme/Theme.kt`
- [x] Создать `ui/chat/TypingIndicator.kt`
- [ ] Переписать `MainActivity.kt` (убрать старый код)
- [ ] Создать `ui/chat/ChatScreen.kt`
- [ ] Создать `ui/settings/SettingsScreen.kt`
- [ ] Добавить Compose Navigation
- [ ] Обновить `app/build.gradle` — добавить `navigation-compose`
- [ ] Собрать и проверить фейковый режим

## Этап 2: UI/UX
- [ ] Экран списка моделей
- [ ] Индикатор загрузки модели в чате
- [ ] Сохранение истории сообщений (in-memory → Room)
- [ ] Настройки темы (светлая/тёмная, цвет акцента)
- [ ] Обработка ошибок с пользовательскими сообщениями

## Этап 3: Реальный локальный LLM
- [ ] Выбрать движок (llama.cpp / MediaPipe / ONNX Runtime)
- [ ] Реализовать `LocalLlmRepository`
- [ ] Импорт .gguf / .bin моделей с устройства
- [ ] Кэширование моделей на диске
- [ ] Проверка работы оффлайн

## Этап 4: Продакшен
- [ ] Hilt / Koin вместо `AppProvider`
- [ ] Room для истории
- [ ] DataStore для настроек
- [ ] ProGuard / R8
- [ ] Release-сборка

## Правила
- После завершения задачи ставить `[x]` и коммитить.
- Если задача большая — разбивать на подзадачи и добавлять в этот список.
- Новые идеи сначала в `STATUS.md`, потом в `TODO.md` как конкретная задача.
