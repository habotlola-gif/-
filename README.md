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
