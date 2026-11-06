package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.NumberSetting;

public class Fullbright
extends Module {
    public final NumberSetting strength = this.setting(new NumberSetting("strength", "\u042f\u0440\u043a\u043e\u0441\u0442\u044c", 100.0, 10.0, 100.0, 5.0, "%"));

    public Fullbright() {
        super("Fullbright", "\u041f\u043e\u043b\u043d\u0430\u044f \u044f\u0440\u043a\u043e\u0441\u0442\u044c \u0432\u0435\u0437\u0434\u0435", Category.VISUALS);
    }

    public float strengthFactor() {
        return this.strength.getFloat() / 100.0f;
    }
}

