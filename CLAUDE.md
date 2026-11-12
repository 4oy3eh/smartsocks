# SmartSocks

Носки/стельки с 6 FSR-датчиками давления на каждой ноге → Bluetooth → приложение: значения датчиков,
center of pressure (COP), запись сессий. Идёт переписывание с нуля; старый код — только как справка.

- Общаться с пользователем по-русски. Код, идентификаторы, коммиты — на английском.
- Протокол устройства, формулы и список багов старой версии: [docs/legacy-analysis.md](docs/legacy-analysis.md).
  Перед работой с BT/парсингом/COP — прочитать его.
- `legacy/` — клоны старых репозиториев, **не редактировать**, не копировать код оттуда как есть.

## Решения

- Платформы: **Android + Desktop (Windows)**, iOS не нужен. Стек: Kotlin Multiplatform + Compose Multiplatform.
- Интерфейс приложения — **на английском**.
- Железо то же (Classic BT SPP). Android: RFCOMM-сокет; Desktop: спаренный носок = COM-порт (jSerialComm).
- Носков сейчас нет → встроенный симулятор обязателен и выбирается в UI как обычное устройство.
- Тестирование: Android-эмулятор (там Bluetooth нет — только симулятор) и desktop-сборка.

## Принципы новой версии

- Протокол и математика (парсер пакетов, калибровка, COP, фильтры) — чистый код без Android-зависимостей,
  покрытый unit-тестами. Android-слой тонкий.
- Транспорт за интерфейсом: реальный SPP/BLE и фейковое устройство (генератор + replay записанных дампов),
  чтобы всё приложение можно было гонять без железа.
- Две ноги — равноправные независимые сессии; ничего статического/глобального на «текущее устройство».
- Поток датчиков (до 200 Гц) не привязан к UI: UI берёт последнее состояние с частотой экрана.
- Никаких аллокаций в горячем цикле парсинга; блокирующее чтение вместо polling.

## Структура

- `core/` — KMP-модуль без Android-зависимостей (сейчас таргет `jvm`):
  `protocol` (пакеты, команды, декодер) · `model` · `analytics` (baseline, нагрузка 1/R, COP, 1€-фильтр, баланс L/R) ·
  `transport` (интерфейс `SockTransport`) · `sim` (модель шага + `SimulatedSockTransport`) · `session` (`SockSession`) ·
  `recording` (CSV/raw запись, потоковое чтение) · `playback` (таймлайн, события шага, `SessionPlayer`) ·
  `settings` (`AppSettings`, кодек, `SettingsRepository`).
- `transport-desktop/` — COM-порт (jSerialComm) + `probe`; `transport-android/` — RFCOMM (AGP 9.5.1,
  `com.android.kotlin.multiplatform.library`, тесты в `androidHostTest`). Android SDK: `ANDROID_HOME`, `local.properties`.
- `app/` — KMP (jvm + android): весь UI на Compose Multiplatform + `LiveController` (две ноги, сэмплинг с частотой
  кадра), `Platform`/`DeviceFactory`, файловое хранилище настроек. `app-desktop/` — `main()`;
  `app-android/` — `com.android.application` (Activity, ViewModel, `SmartSocksApplication`).

## Команды

- JDK 21 портативный: `~/.jdks/jdk-21*` (user `JAVA_HOME`). Если в shell нет `JAVA_HOME` — выставить на него.
- `./gradlew check` — всё (ktlint через Spotless + тесты); `./gradlew :core:jvmTest` — тесты ядра;
  `./gradlew spotlessApply` — автоформат.
- Запуск: `./gradlew :app-desktop:run`; Android — эмулятор `emulator -avd smartsocks`, затем
  `./gradlew :app-android:installDebug`. Скриншоты UI без окна: `./gradlew :app-desktop:renderScreens`
  (→ `app-desktop/build/screens/`). Скриншот окна — только `PrintWindow` (не `CopyFromScreen`: снимает чужие окна).

## Проверка изменений

- Stop-хук `.claude/hooks/verify.py`: если менялись `.kt/.kts/.toml/.properties`, гоняет `spotlessApply check` и
  блокирует завершение при ошибке. Не обходить — чинить.
- Новая логика протокола/математики/симулятора — только вместе с тестами в `commonTest` (виртуальное время `runTest`).
- Перед коммитом крупного изменения — `/code-review` или агент `code-reviewer`. CI: `.github/workflows/ci.yml`.
