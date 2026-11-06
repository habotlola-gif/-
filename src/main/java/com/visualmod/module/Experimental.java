package com.visualmod.module;

import com.visualmod.setting.NumberSetting;

/**
 * Экспериментальные функции KillAura (категория Misc).
 * Пока модуль выключен, KillAura работает на оптимальных встроенных значениях
 * (упреждение {@link #DEFAULT_PREDICT} т., скорость {@link #DEFAULT_SPEED} °/с).
 * Включишь модуль — KillAura возьмёт значения отсюда.
 */
public final class Experimental extends Module {
    public static final double DEFAULT_PREDICT = 1.0;
    public static final double DEFAULT_SPEED = 540.0;

    public final NumberSetting predict = this.setting(new NumberSetting("predict", "Упреждение", DEFAULT_PREDICT, 0.0, 4.0, 0.25, " т."));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "Скорость наведения", DEFAULT_SPEED, 20.0, 1080.0, 10.0, "°/с"));

    public Experimental() {
        super("Experimental", "Экспериментальные настройки KillAura: упреждение и скорость", Category.MISC);
    }

    public float predictTicks() {
        return (float) (this.isEnabled() ? this.predict.get() : DEFAULT_PREDICT);
    }

    public float speedDegPerSec() {
        return (float) (this.isEnabled() ? this.speed.get() : DEFAULT_SPEED);
    }
}
