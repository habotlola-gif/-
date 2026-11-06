package com.visualmod.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.visualmod.setting.Setting;

public class BooleanSetting
extends Setting<Boolean> {
    private boolean value;

    public BooleanSetting(String id, String name, boolean defaultValue) {
        super(id, name);
        this.value = defaultValue;
    }

    @Override
    public Boolean get() {
        return this.value;
    }

    public boolean isOn() {
        return this.value;
    }

    public void toggle() {
        this.value = !this.value;
    }

    @Override
    public String displayValue() {
        return this.value ? "\u0412\u043a\u043b" : "\u0412\u044b\u043a\u043b";
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive(Boolean.valueOf(this.value));
    }

    @Override
    public void load(JsonElement json) {
        this.value = json.getAsBoolean();
    }
}

