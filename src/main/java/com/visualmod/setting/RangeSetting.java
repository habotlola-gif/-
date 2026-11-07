package com.visualmod.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.visualmod.setting.Setting;
import java.util.Locale;
import net.minecraft.util.RandomSource;

public class RangeSetting
extends Setting<double[]> {
    private final double min;
    private final double max;
    private final double step;
    private final String suffix;
    private double low;
    private double high;

    public RangeSetting(String id, String name, double defaultLow, double defaultHigh, double min, double max, double step, String suffix) {
        super(id, name);
        this.min = min;
        this.max = max;
        this.step = step;
        this.suffix = suffix;
        this.set(defaultLow, defaultHigh);
    }

    private double snap(double value) {
        double clamped = Math.max(this.min, Math.min(this.max, value));
        return this.min + (double)Math.round((clamped - this.min) / this.step) * this.step;
    }

    public void set(double newLow, double newHigh) {
        this.low = this.snap(Math.min(newLow, newHigh));
        this.high = this.snap(Math.max(newLow, newHigh));
    }

    public double getLow() {
        return this.low;
    }

    public double getHigh() {
        return this.high;
    }

    @Override
    public double[] get() {
        return new double[]{this.low, this.high};
    }

    public double lowProgress() {
        return (this.low - this.min) / (this.max - this.min);
    }

    public double highProgress() {
        return (this.high - this.min) / (this.max - this.min);
    }

    public void setHandleProgress(int handle, double progress) {
        double value = this.snap(this.min + (this.max - this.min) * progress);
        if (handle == 0) {
            this.low = Math.min(value, this.high);
        } else {
            this.high = Math.max(value, this.low);
        }
    }

    public double random(RandomSource random) {
        return this.low + (this.high - this.low) * random.nextDouble();
    }

    @Override
    public String displayValue() {
        return this.format(this.low) + "\u2013" + this.format(this.high) + this.suffix;
    }

    private String format(double value) {
        return this.step >= 1.0 ? Integer.toString((int)Math.rint(value)) : String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public JsonElement save() {
        JsonArray array = new JsonArray();
        array.add((Number)this.low);
        array.add((Number)this.high);
        return array;
    }

    @Override
    public void load(JsonElement json) {
        JsonArray array = json.getAsJsonArray();
        this.set(array.get(0).getAsDouble(), array.get(1).getAsDouble());
    }
}

