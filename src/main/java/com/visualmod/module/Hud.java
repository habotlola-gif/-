package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;

public class Hud
extends Module {
    public final BooleanSetting keybinds = this.setting(new BooleanSetting("keybinds", "\u041f\u043b\u0430\u0448\u043a\u0430 \u0431\u0438\u043d\u0434\u043e\u0432", true));
    public final BooleanSetting onlyEnabled = this.setting(new BooleanSetting("only_enabled", "\u0422\u043e\u043b\u044c\u043a\u043e \u0432\u043a\u043b\u044e\u0447\u0451\u043d\u043d\u044b\u0435", false));
    public final BooleanSetting targetHud = this.setting(new BooleanSetting("target_hud", "\u0422\u0430\u0440\u0433\u0435\u0442 HUD", true));
    public final NumberSetting keybindsX = this.setting(new NumberSetting("keybinds_x", "Keybinds X", 0.012, 0.0, 1.0, 5.0E-4, "").hidden());
    public final NumberSetting keybindsY = this.setting(new NumberSetting("keybinds_y", "Keybinds Y", 0.32, 0.0, 1.0, 5.0E-4, "").hidden());
    public final NumberSetting targetX = this.setting(new NumberSetting("target_x", "Target X", 0.54, 0.0, 1.0, 5.0E-4, "").hidden());
    public final NumberSetting targetY = this.setting(new NumberSetting("target_y", "Target Y", 0.68, 0.0, 1.0, 5.0E-4, "").hidden());

    public Hud() {
        super("Hud", "\u041f\u043b\u0430\u0448\u043a\u0430 \u0431\u0438\u043d\u0434\u043e\u0432 \u0438 \u0442\u0430\u0440\u0433\u0435\u0442 HUD", Category.VISUALS);
        this.setEnabled(true);
    }
}

