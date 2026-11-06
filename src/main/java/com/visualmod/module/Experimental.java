package com.visualmod.module;

import com.visualmod.gui.MultipointEditorScreen;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ButtonSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;

/**
 * Экспериментальные функции KillAura (категория Misc): упреждение, скорость наведения и Multipoint.
 * Модуль выключен: Multipoint выключен (прицел в фиксированную точку корпуса), упреждение и скорость
 * наведения берутся оптимальные встроенные. Модуль включён: Multipoint работает по пути из редактора
 * и всем настройкам ниже, упреждение и скорость тоже берутся отсюда.
 */
public final class Experimental extends Module {
    public static final double DEFAULT_PREDICT = 1.0;
    public static final double DEFAULT_SPEED = 540.0;
    public static final double DEFAULT_MP_SPEED = 1.0;
    public static final int DEFAULT_MP_ARC = 2;
    public static final double DEFAULT_MP_SIZE = 80.0;

    public final NumberSetting predict = this.setting(new NumberSetting("predict", "Упреждение", DEFAULT_PREDICT, 0.0, 4.0, 0.25, " т."));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "Скорость наведения", DEFAULT_SPEED, 20.0, 1080.0, 10.0, "°/с"));

    public final BooleanSetting multipoint = this.setting(new BooleanSetting("multipoint", "Multipoint", true));
    public final NumberSetting mpSpeed = this.setting(new NumberSetting("mp_speed", "Скорость обхода", DEFAULT_MP_SPEED, 0.1, 4.0, 0.1, " об/с"));
    public final NumberSetting mpArc = this.setting(new NumberSetting("mp_arc", "Дуга (сглаживание)", DEFAULT_MP_ARC, 0.0, 4.0, 1.0, ""));
    public final NumberSetting mpSize = this.setting(new NumberSetting("mp_size", "Размер пути", DEFAULT_MP_SIZE, 20.0, 100.0, 5.0, "%"));
    public final ModeSetting mpDirection = this.setting(new ModeSetting("mp_direction", "Направление", "Вперёд", "Назад", "Туда-обратно"));
    public final ButtonSetting mpOpen = this.setting(new ButtonSetting("mp_open", "Открыть расположение",
            () -> Minecraft.getInstance().setScreen(new MultipointEditorScreen(Minecraft.getInstance().screen))));

    public Experimental() {
        super("Experimental", "Экспериментальные настройки KillAura: упреждение, скорость, Multipoint", Category.MISC);
        this.mpSpeed.visibleWhen(this.multipoint::isOn);
        this.mpArc.visibleWhen(this.multipoint::isOn);
        this.mpSize.visibleWhen(this.multipoint::isOn);
        this.mpDirection.visibleWhen(this.multipoint::isOn);
        this.mpOpen.visibleWhen(this.multipoint::isOn);
    }

    public float predictTicks() {
        return (float) (this.isEnabled() ? this.predict.get() : DEFAULT_PREDICT);
    }

    public float speedDegPerSec() {
        return (float) (this.isEnabled() ? this.speed.get() : DEFAULT_SPEED);
    }

    /** Multipoint работает только когда включён модуль Experimental (и переключатель Multipoint). */
    public boolean multipointOn() {
        return this.isEnabled() && this.multipoint.isOn();
    }

    public double mpSpeedValue() {
        return this.isEnabled() ? this.mpSpeed.get() : DEFAULT_MP_SPEED;
    }

    public int mpArcValue() {
        return this.isEnabled() ? this.mpArc.getInt() : DEFAULT_MP_ARC;
    }

    public float mpSizeValue() {
        return (float) ((this.isEnabled() ? this.mpSize.get() : DEFAULT_MP_SIZE) / 100.0);
    }

    /** Направление обхода: 0 вперёд, 1 назад, 2 туда-обратно. */
    public int mpDirectionIndex() {
        return this.isEnabled() ? this.mpDirection.getIndex() : 0;
    }
}
