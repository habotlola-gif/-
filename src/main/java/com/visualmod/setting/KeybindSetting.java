package com.visualmod.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.visualmod.module.Keybind;
import com.visualmod.setting.Setting;

public class KeybindSetting
extends Setting<Integer> {
    private int bind;

    public KeybindSetting(String id, String name, int defaultBind) {
        super(id, name);
        this.bind = defaultBind;
    }

    @Override
    public Integer get() {
        return this.bind;
    }

    public int getBind() {
        return this.bind;
    }

    public void setBind(int bind) {
        this.bind = bind;
    }

    public boolean matches(int pressed) {
        return this.bind != -1 && this.bind == pressed;
    }

    @Override
    public String displayValue() {
        return Keybind.displayName(this.bind);
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive((Number)this.bind);
    }

    @Override
    public void load(JsonElement json) {
        this.bind = json.getAsInt();
    }
}

