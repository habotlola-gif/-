package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.NumberSetting;

public class AspectRatio
extends Module {
    public final NumberSetting ratio = this.setting(new NumberSetting("ratio", "\u0421\u043e\u043e\u0442\u043d\u043e\u0448\u0435\u043d\u0438\u0435", 1.33, 0.8, 2.5, 0.01, ""));

    public AspectRatio() {
        super("AspectRatio", "\u0420\u0430\u0441\u0442\u044f\u043d\u0443\u0442\u043e\u0435 \u0441\u043e\u043e\u0442\u043d\u043e\u0448\u0435\u043d\u0438\u0435 \u0441\u0442\u043e\u0440\u043e\u043d", Category.VISUALS);
    }

    public float aspect(float windowAspect) {
        return this.isEnabled() ? this.ratio.getFloat() : windowAspect;
    }
}

