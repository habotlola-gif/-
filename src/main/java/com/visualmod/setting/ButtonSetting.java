package com.visualmod.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** Кнопка в настройках модуля: ничего не хранит, по нажатию вызывает действие. */
public class ButtonSetting extends Setting<Void> {
    private final Runnable action;

    public ButtonSetting(String id, String name, Runnable action) {
        super(id, name);
        this.action = action;
    }

    public void press() {
        this.action.run();
    }

    @Override
    public Void get() {
        return null;
    }

    @Override
    public String displayValue() {
        return "";
    }

    @Override
    public JsonElement save() {
        return JsonNull.INSTANCE;
    }

    @Override
    public void load(JsonElement json) {
    }
}
