# Visual Mod

Клиентский мод для Minecraft **1.21.11** (Fabric, Java 21) для HvH-академии.

Проект восстановлен из собранного `visualmod-1.0.0.jar` (исходники были утеряны): байткод перенесён на
Mojang-названия, декомпилирован и собран в обычный Gradle-проект. Поверх него добавлена новая **Silent KillAura**.

## Модули
Combat: **KillAura** (silent), AimAssist, TriggerBot, AutoMace, **AutoSwap** (сфера / тотем), ClickActions, FTHelper, SprintResetter
Движение и прочее: Sprint, NoJumpDelay, FastExp, Fullbright
Визуал: ClickGUI, HUD, NameTags, TargetEsp, ShaderEsp, ShaderHands, Ambience, ViewModel, SwingAnimations, AspectRatio, Removals

## Сборка
```
./gradlew build        # jar в build/libs/
./gradlew runClient    # запуск клиента для теста
```
Нужен JDK 21. Если `gradlew` нет — установи Gradle 8.14+ и выполни `gradle wrapper`.

## Silent KillAura
Подробности в `src/main/java/com/visualmod/module/KillAura.java`. Коротко:
- на время тика игрока подставляется поворот на цель (его видит сервер), потом камера возвращается;
- удар идёт в начале следующего тика, до пакета движения (ванильный порядок), по повороту, который сервер уже знает;
- повороты привязаны к сетке чувствительности мыши (GCD);
- коррекция движения (`KeyboardInputSilentMixin`): W/A/S/D пересчитываются так, чтобы идти туда, куда смотрит камера;
- дальность считается до ближайшей точки хитбокса; по умолчанию 3.0.

## Заметки по восстановленному коду
Код получен декомпиляцией (CFR), поэтому местами выглядит не так, как писал человек (имена переменных, лишние
приведения типов). Логика сохранена. Файлы `assets/` и шейдеры взяты из jar без изменений.
