package com.visualmod.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.visualmod.setting.Setting;
import java.util.List;

public class ModeSetting
extends Setting<String> {
    private final List<String> modes;
    private int index;

    public ModeSetting(String id, String name, String ... modes) {
        super(id, name);
        this.modes = List.of(modes);
    }

    @Override
    public String get() {
        return this.modes.get(this.index);
    }

    public int getIndex() {
        return this.index;
    }

    public List<String> getModes() {
        return this.modes;
    }

    public void setIndex(int newIndex) {
        this.index = Math.floorMod(newIndex, this.modes.size());
    }

    public void cycle(int direction) {
        this.index = Math.floorMod(this.index + direction, this.modes.size());
    }

    @Override
    public String displayValue() {
        return this.get();
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive(this.get());
    }

    @Override
    public void load(JsonElement json) {
        int found = this.modes.indexOf(json.getAsString());
        if (found >= 0) {
            this.index = found;
        }
    }
}

