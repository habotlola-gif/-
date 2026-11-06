package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ColorSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;

public class ShaderHands
extends Module {
    public static final int MODE_BOTH = 0;
    public static final int MODE_FILL = 1;
    public static final int MODE_GLOW = 2;
    public final ModeSetting mode = this.setting(new ModeSetting("mode", "\u0420\u0435\u0436\u0438\u043c", "\u0417\u0430\u043b\u0438\u0432\u043a\u0430 + \u0441\u0432\u0435\u0447\u0435\u043d\u0438\u0435", "\u0422\u043e\u043b\u044c\u043a\u043e \u0437\u0430\u043b\u0438\u0432\u043a\u0430", "\u0422\u043e\u043b\u044c\u043a\u043e \u0441\u0432\u0435\u0447\u0435\u043d\u0438\u0435"));
    public final ColorSetting colorA = this.setting(new ColorSetting("color_a", "\u0426\u0432\u0435\u0442 1", 262.0f));
    public final ColorSetting colorB = this.setting(new ColorSetting("color_b", "\u0426\u0432\u0435\u0442 2", 188.0f));
    public final NumberSetting fill = this.setting(new NumberSetting("fill", "\u0417\u0430\u043b\u0438\u0432\u043a\u0430", 70.0, 0.0, 100.0, 5.0, "%"));
    public final NumberSetting glow = this.setting(new NumberSetting("glow", "\u0421\u0432\u0435\u0447\u0435\u043d\u0438\u0435", 16.0, 2.0, 30.0, 1.0, " px"));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "\u0421\u043a\u043e\u0440\u043e\u0441\u0442\u044c", 1.0, 0.0, 3.0, 0.1, "x"));
    public final BooleanSetting trail = this.setting(new BooleanSetting("trail", "\u041e\u0433\u043d\u0435\u043d\u043d\u044b\u0439 \u0448\u043b\u0435\u0439\u0444", true));
    public final ModeSetting trailPalette = this.setting(new ModeSetting("trail_palette", "\u0426\u0432\u0435\u0442 \u0448\u043b\u0435\u0439\u0444\u0430", "\u041e\u0433\u043e\u043d\u044c", "\u0426\u0432\u0435\u0442\u0430 \u0440\u0443\u043a"));
    public final NumberSetting trailLength = this.setting(new NumberSetting("trail_length", "\u0414\u043b\u0438\u043d\u0430 \u0448\u043b\u0435\u0439\u0444\u0430", 100.0, 30.0, 250.0, 10.0, "%"));
    public final NumberSetting trailBrightness = this.setting(new NumberSetting("trail_brightness", "\u042f\u0440\u043a\u043e\u0441\u0442\u044c \u0448\u043b\u0435\u0439\u0444\u0430", 100.0, 20.0, 200.0, 10.0, "%"));

    public ShaderHands() {
        super("ShaderHands", "\u0413\u0440\u0430\u0434\u0438\u0435\u043d\u0442\u043d\u044b\u0435 \u0440\u0443\u043a\u0438 \u0441\u043e \u0441\u0432\u0435\u0447\u0435\u043d\u0438\u0435\u043c", Category.VISUALS);
        this.trailPalette.visibleWhen(this.trail::isOn);
        this.trailLength.visibleWhen(this.trail::isOn);
        this.trailBrightness.visibleWhen(this.trail::isOn);
    }
}

