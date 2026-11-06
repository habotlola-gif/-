package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;

public class Removals
extends Module {
    public static final int FIRE_LOWER = 0;
    public static final int FIRE_REMOVE = 1;
    public final ModeSetting fire = this.setting(new ModeSetting("fire", "\u041e\u0433\u043e\u043d\u044c", "\u041d\u0438\u0436\u0435", "\u0423\u0431\u0440\u0430\u0442\u044c", "\u041a\u0430\u043a \u0432 \u0438\u0433\u0440\u0435"));
    public final NumberSetting fireHeight = this.setting(new NumberSetting("fire_height", "\u0412\u044b\u0441\u043e\u0442\u0430 \u043e\u0433\u043d\u044f", 40.0, 0.0, 100.0, 5.0, "%"));
    public final BooleanSetting nausea = this.setting(new BooleanSetting("nausea", "\u0422\u043e\u0448\u043d\u043e\u0442\u0430 \u0438 \u043f\u043e\u0440\u0442\u0430\u043b", true));
    public final BooleanSetting hurtCam = this.setting(new BooleanSetting("hurt_cam", "\u0422\u0440\u044f\u0441\u043a\u0430 \u043e\u0442 \u0443\u0440\u043e\u043d\u0430", true));
    public final BooleanSetting blindness = this.setting(new BooleanSetting("blindness", "\u0421\u043b\u0435\u043f\u043e\u0442\u0430 \u0438 \u0442\u044c\u043c\u0430", true));
    public final BooleanSetting overlays = this.setting(new BooleanSetting("overlays", "\u0422\u044b\u043a\u0432\u0430 \u0438 \u0438\u043d\u0435\u0439", true));
    public final BooleanSetting fluidOverlay = this.setting(new BooleanSetting("fluid_overlay", "\u0412\u043e\u0434\u0430 \u0438 \u0431\u043b\u043e\u043a\u0438 \u043d\u0430 \u044d\u043a\u0440\u0430\u043d\u0435", true));
    public final BooleanSetting totem = this.setting(new BooleanSetting("totem", "\u0410\u043d\u0438\u043c\u0430\u0446\u0438\u044f \u0442\u043e\u0442\u0435\u043c\u0430", false));

    public Removals() {
        super("Removals", "\u0423\u0431\u0438\u0440\u0430\u0435\u0442 \u043e\u0433\u043e\u043d\u044c, \u0442\u043e\u0448\u043d\u043e\u0442\u0443 \u0438 \u0434\u0440\u0443\u0433\u0438\u0435 \u043f\u043e\u043c\u0435\u0445\u0438", Category.VISUALS);
    }

    public boolean removesFire() {
        return this.isEnabled() && this.fire.getIndex() == 1;
    }

    public float fireOffset() {
        if (!this.isEnabled() || this.fire.getIndex() != 0) {
            return 0.0f;
        }
        return -0.5f * (1.0f - this.fireHeight.getFloat() / 100.0f);
    }

    public boolean removes(BooleanSetting setting) {
        return this.isEnabled() && setting.isOn();
    }
}

