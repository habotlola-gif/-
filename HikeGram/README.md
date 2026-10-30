# HikeGram

Форк Telegram для Android (DrKLO/Telegram, GPLv2). Здесь лежат только мои правки в виде патча.

- Режим призрака: не отправляются статус «в сети» и «печатает».
- Сохранение удалённых собеседником сообщений.
- Название HikeGram, пакет `com.hikegram.app`, только arm64.
- Плагин Google Services отключён (нужен свой Firebase-проект).

## Сборка
1. `git clone https://github.com/DrKLO/Telegram && cd Telegram && git submodule update --init --depth 1`
2. `git apply hikegram.patch`
3. Android SDK 36, NDK 27.2.12479018, CMake 3.22.1.
4. `./gradlew :TMessagesProj_App:assembleAfatDebug`

Перед публикацией замените `APP_ID` и `APP_HASH` в `BuildVars.java` на свои (my.telegram.org).
