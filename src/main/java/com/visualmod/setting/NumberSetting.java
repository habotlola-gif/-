package com.visualmod.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.visualmod.setting.Setting;
import java.util.Locale;

public class NumberSetting
extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;
    private final String suffix;
    private double value;

    public NumberSetting(String id, String name, double defaultValue, double min, double max, double step, String suffix) {
        super(id, name);
        this.min = min;
        this.max = max;
        this.step = step;
        this.suffix = suffix;
        this.set(defaultValue);
    }

    public NumberSetting hidden() {
        this.markHidden();
        return this;
    }

    @Override
    public Double get() {
        return this.value;
    }

    public int getInt() {
        return (int)Math.round(this.value);
    }

    public float getFloat() {
        return (float)this.value;
    }

    public void set(double newValue) {
        double clamped = Math.max(this.min, Math.min(this.max, newValue));
        this.value = this.min + (double)Math.round((clamped - this.min) / this.step) * this.step;
    }

    public double getProgress() {
        return (this.value - this.min) / (this.max - this.min);
    }

    public void setProgress(double progress) {
        this.set(this.min + (this.max - this.min) * progress);
    }

    @Override
    public String displayValue() {
        boolean whole = this.step >= 1.0 && Math.abs(this.value - Math.rint(this.value)) < 1.0E-6;
        String format = this.step < 0.1 ? "%.2f" : "%.1f";
        String number = whole ? Integer.toString((int)Math.rint(this.value)) : String.format(Locale.ROOT, format, this.value);
        return number + this.suffix;
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive((Number)this.value);
    }

    @Override
    public void load(JsonElement json) {
        this.set(json.getAsDouble());
    }
}

