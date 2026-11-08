# HvH Client (Minecraft PE, Android)

Каркас клиента для HvH-серверов (где читы разрешены).

## Структура
- `app/src/main/java/com/hvh/client` — лаунчер: меню модулей, настройки, запуск MCPE, экспорт `hvh.cfg`.
- `app/src/main/cpp` — нативная библиотека `libhvhclient.so`: `Module`/`ModuleManager`, заглушки модулей.

## Сборка
Открыть в Android Studio (нужны SDK 34 + NDK + CMake) → Build APK.

## Что ещё нужно сделать
1. Инъекция `libhvhclient.so` в MCPE (напр. патч APK с `System.loadLibrary` или загрузка через мод-лоадер).
2. Хуки на игровой тик и функции атаки/движения под конкретную версию MCPE (оффсеты/сигнатуры).
3. Реализовать тела модулей (сейчас заглушки).
4. Внутриигровое меню (ImGui overlay).
