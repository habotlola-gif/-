package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.ColorSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;

public class TargetEsp
extends Module {
    public static final String MODE_GHOSTS = "\u041f\u0440\u0438\u0437\u0440\u0430\u043a\u0438";
    public static final String MODE_ARROWS = "\u0421\u0442\u0440\u0435\u043b\u043a\u0438";
    public static final String MODE_RING = "\u041a\u043e\u043b\u044c\u0446\u043e";
    public static final String MODE_LIGHTNING = "\u041c\u043e\u043b\u043d\u0438\u044f";
    public static final String MODE_CRYSTALS = "\u041a\u0440\u0438\u0441\u0442\u0430\u043b\u043b\u044b";
    public final ModeSetting mode = this.setting(new ModeSetting("mode", "\u0420\u0435\u0436\u0438\u043c", "\u041f\u0440\u0438\u0437\u0440\u0430\u043a\u0438", "\u0421\u0442\u0440\u0435\u043b\u043a\u0438", "\u041a\u043e\u043b\u044c\u0446\u043e", "\u041c\u043e\u043b\u043d\u0438\u044f", "\u041a\u0440\u0438\u0441\u0442\u0430\u043b\u043b\u044b"));
    public final NumberSetting ghosts = this.setting(new NumberSetting("ghosts", "\u041f\u0440\u0438\u0437\u0440\u0430\u043a\u043e\u0432", 3.0, 1.0, 6.0, 1.0, ""));
    public final NumberSetting size = this.setting(new NumberSetting("size", "\u0420\u0430\u0437\u043c\u0435\u0440", 100.0, 40.0, 200.0, 5.0, "%"));
    public final NumberSetting thickness = this.setting(new NumberSetting("thickness", "\u0422\u043e\u043b\u0449\u0438\u043d\u0430", 100.0, 50.0, 200.0, 5.0, "%"));
    public final NumberSetting trailLength = this.setting(new NumberSetting("trail_length", "\u0414\u043b\u0438\u043d\u0430 \u0445\u0432\u043e\u0441\u0442\u0430", 100.0, 30.0, 200.0, 5.0, "%"));
    public final NumberSetting glow = this.setting(new NumberSetting("glow", "\u0421\u0432\u0435\u0447\u0435\u043d\u0438\u0435", 100.0, 0.0, 200.0, 5.0, "%"));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "\u0421\u043a\u043e\u0440\u043e\u0441\u0442\u044c", 1.0, 0.2, 3.0, 0.1, "x"));
    public final ColorSetting color = this.setting(new ColorSetting("color_a", "\u0426\u0432\u0435\u0442", 318.0f));

    public TargetEsp() {
        super("TargetESP", "\u041f\u043e\u0434\u0441\u0432\u0435\u0442\u043a\u0430 \u0446\u0435\u043b\u0438", Category.VISUALS);
        this.ghosts.visibleWhen(this::isGhosts);
        this.trailLength.visibleWhen(this::isGhosts);
        this.thickness.visibleWhen(() -> this.isArrows() || this.isRing() || this.isLightning());
    }

    public boolean isRing() {
        return MODE_RING.equals(this.mode.get());
    }

    public boolean isCrystals() {
        return MODE_CRYSTALS.equals(this.mode.get());
    }

    public boolean isLightning() {
        return MODE_LIGHTNING.equals(this.mode.get());
    }

    public boolean isGhosts() {
        return MODE_GHOSTS.equals(this.mode.get());
    }

    public boolean isArrows() {
        return MODE_ARROWS.equals(this.mode.get());
    }
}

