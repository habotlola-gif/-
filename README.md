# HvH Client (Minecraft PE, Android)

Каркас клиента для HvH-серверов (где читы разрешены).

## Структура
- `app/src/main/java/com/hvh/client` — лаунчер: меню модулей, настройки, запуск MCPE, экспорт `hvh.cfg`.
- `app/src/main/cpp` — нативная библиотека `libhvhclient.so`: `Module`/`ModuleManager`, заглушки модулей.

## Сборка
Открыть в Android Studio (нужны SDK 34 + NDK + CMake) → Build APK.

## Готово
- `sigscan.h` — поиск функций по шаблону с масками (проверен на libminecraftpe.so: 365 МБ за ~0.3 с). Тест: `tests/sigscan_test.cpp`.
- `hook.h` / `memory.h` — inline-хук ARM64 с трамплином и доступ к памяти модуля (на устройстве не проверялись).
- `tools/inject_apk.py` — патч APK для загрузки `libhvhclient.so` (не проверялся на устройстве).
- Нативный код ждёт загрузки игры и ищет функции по сигнатурам.

## Что ещё нужно сделать
1. Найти сигнатуры игровых функций (в `libminecraftpe.so` нет имён классов Mojang — только RTTI/vtable/строки).
2. Хуки на игровой тик и функции атаки/движения под конкретную версию MCPE (оффсеты/сигнатуры).
3. Реализовать тела модулей (сейчас заглушки).
4. Внутриигровое меню (ImGui overlay).

## Реверс (libminecraftpe.so, ARM64)
- В файле нет имён функций Mojang, но остались RTTI-имена классов. `tools/vtables.py <so> LocalPlayer Player Actor` находит таблицы виртуальных функций по RTTI.
- Для проверенной сборки: у `LocalPlayer` 247 виртуальных функций, у `Player` 181, у `Actor` 137.
- Игра использует ECS-системы (напр. `SprintTriggerSystem::doIntentTick`), строки с их сигнатурами есть в файле.
- `MCPE_SO=libminecraftpe.so python3 tools/analyze.py <адрес слота vtable>` — для каждой виртуальной функции печатает размер и строки, на которые она ссылается. Строки-assert'ы содержат имена методов Mojang (напр. `LocalPlayer::changeDimension`), так что часть индексов можно подписать.
- Подписано в vtable `LocalPlayer` (индексы относительно первой виртуальной функции): 88/89 `changeDimension`, 135 `readAdditionalSaveData`, 192 `openBook`, 216 `addExperience`, 217 `addLevels`. Тик пока не найден — похоже, значительная часть логики (спринт и др.) перенесена в ECS-системы.

### Спринт (сборка из файла пользователя, ECS-системы)
Нашёл через RTTI-имена адаптеров `StrictTickingSystemFunctionAdapter<...>` их vtable (функции запуска систем):
- `SprintTimerSystemImpl::tick` — обёртка `0xe2dce1c`, внутренняя функция `0xe2dd040`.
- `SprintTriggerSystem::doSetRequestTick` — обёртка `0xe2ddfb4`.
- `SprintTriggerSystem::doIntentTick` — обёртка `0xe2df36c`; крупная функция `0xe2dfb34` (336 инструкций) — кандидат на саму логику.
Адреса действительны только для этой сборки. Данные ECS-компонентов (PlayerInputRequestComponent и др.) — отдельная работа по раскладке структур.
Запасной путь для AutoSprint: игровая опция `SPRINT_ON_MOVEMENT` (строка есть в файле).
