package com.visualmod.setting;

import com.google.gson.JsonElement;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String id;
    private final String name;
    private boolean hidden;
    private BooleanSupplier visible = () -> true;

    protected Setting(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isHidden() {
        return this.hidden || !this.visible.getAsBoolean();
    }

    public void visibleWhen(BooleanSupplier condition) {
        this.visible = condition;
    }

    protected void markHidden() {
        this.hidden = true;
    }

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public abstract T get();

    public abstract String displayValue();

    public abstract JsonElement save();

    public abstract void load(JsonElement var1);
}

