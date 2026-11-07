package com.visualmod.module;

import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ColorSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;

/** Частицы-звёздочки: вокруг тебя, вокруг цели и вспышкой при ударе. Форма как на референсе (скруглённая звезда). */
public class Particles extends Module {
    /** Острая: классическая звезда. Мягкая: скруглённая, как на исходном референсе. */
    public final ModeSetting shape = this.setting(new ModeSetting("shape", "Форма", "Острая", "Мягкая"));
    public final BooleanSetting aroundMe = this.setting(new BooleanSetting("around_me", "Вокруг меня", true));
    public final BooleanSetting aroundTarget = this.setting(new BooleanSetting("around_target", "Вокруг цели", true));
    public final BooleanSetting onHit = this.setting(new BooleanSetting("on_hit", "При ударе", true));
    public final ColorSetting color = this.setting(new ColorSetting("color_a", "Цвет", 318.0f));
    public final BooleanSetting rainbow = this.setting(new BooleanSetting("rainbow", "Радужные", false));
    public final BooleanSetting fill = this.setting(new BooleanSetting("fill", "Заливка", false));
    public final NumberSetting size = this.setting(new NumberSetting("size", "Размер", 100.0, 30.0, 300.0, 5.0, "%"));
    public final NumberSetting amount = this.setting(new NumberSetting("amount", "Количество", 10.0, 1.0, 40.0, 1.0, "/с"));
    public final NumberSetting lifetime = this.setting(new NumberSetting("lifetime", "Время жизни", 2.0, 0.5, 5.0, 0.1, " с"));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "Скорость", 100.0, 0.0, 300.0, 10.0, "%"));
    public final NumberSetting rise = this.setting(new NumberSetting("rise", "Подъём / падение", 30.0, -100.0, 100.0, 5.0, "%"));
    public final NumberSetting spin = this.setting(new NumberSetting("spin", "Вращение", 100.0, 0.0, 400.0, 10.0, "%"));
    public final NumberSetting thickness = this.setting(new NumberSetting("thickness", "Толщина контура", 100.0, 40.0, 250.0, 5.0, "%"));
    public final NumberSetting glow = this.setting(new NumberSetting("glow", "Свечение", 100.0, 0.0, 200.0, 5.0, "%"));

    public Particles() {
        super("Particles", "Частицы-звёздочки вокруг игрока, цели и при ударах", Category.VISUALS);
        this.thickness.visibleWhen(() -> !this.fill.isOn());
    }
}
