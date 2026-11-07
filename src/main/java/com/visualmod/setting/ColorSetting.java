package com.visualmod.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.visualmod.setting.Setting;
import java.util.Locale;
import net.minecraft.util.Mth;

public class ColorSetting
extends Setting<Float> {
    public static final float SATURATION = 0.65f;
    public static final float BRIGHTNESS = 0.97f;
    private float hue;
    private float saturation = 0.65f;
    private float brightness = 0.97f;

    public ColorSetting(String id, String name, float defaultHue) {
        super(id, name);
        this.setHue(defaultHue);
    }

    @Override
    public Float get() {
        return Float.valueOf(this.hue);
    }

    public float getHue() {
        return this.hue;
    }

    public float getSaturation() {
        return this.saturation;
    }

    public float getBrightness() {
        return this.brightness;
    }

    public void setHue(float newHue) {
        this.hue = Math.max(0.0f, Math.min(360.0f, newHue));
    }

    public void setSaturation(float value) {
        this.saturation = Math.max(0.0f, Math.min(1.0f, value));
    }

    public void setBrightness(float value) {
        this.brightness = Math.max(0.0f, Math.min(1.0f, value));
    }

    public void setRgb(int rgb) {
        float min;
        float r = (float)(rgb >> 16 & 0xFF) / 255.0f;
        float g = (float)(rgb >> 8 & 0xFF) / 255.0f;
        float b = (float)(rgb & 0xFF) / 255.0f;
        float max = Math.max(r, Math.max(g, b));
        float delta = max - (min = Math.min(r, Math.min(g, b)));
        if (delta > 1.0E-5f) {
            float h = max == r ? (g - b) / delta % 6.0f : (max == g ? (b - r) / delta + 2.0f : (r - g) / delta + 4.0f);
            this.setHue(h < 0.0f ? h + 360.0f : (h *= 60.0f));
        }
        this.setSaturation(max <= 0.0f ? 0.0f : delta / max);
        this.setBrightness(max);
    }

    public float getProgress() {
        return this.hue / 360.0f;
    }

    public void setProgress(double progress) {
        this.setHue((float)(progress * 360.0));
    }

    public int rgb() {
        return 0xFF000000 | Mth.hsvToRgb((float)(this.hue % 360.0f / 360.0f), (float)this.saturation, (float)this.brightness);
    }

    public static int pureHue(float hue) {
        return 0xFF000000 | Mth.hsvToRgb((float)(hue % 360.0f / 360.0f), (float)1.0f, (float)1.0f);
    }

    public static int colorForHue(float hue) {
        return 0xFF000000 | Mth.hsvToRgb((float)(hue % 360.0f / 360.0f), (float)0.65f, (float)0.97f);
    }

    public String hex() {
        return String.format(Locale.ROOT, "#%06X", this.rgb() & 0xFFFFFF);
    }

    @Override
    public String displayValue() {
        return Math.round(this.hue) + "\u00b0";
    }

    @Override
    public JsonElement save() {
        JsonObject json = new JsonObject();
        json.addProperty("h", (Number)Float.valueOf(this.hue));
        json.addProperty("s", (Number)Float.valueOf(this.saturation));
        json.addProperty("v", (Number)Float.valueOf(this.brightness));
        return json;
    }

    @Override
    public void load(JsonElement json) {
        if (json.isJsonPrimitive()) {
            this.setHue(json.getAsFloat());
            return;
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("h")) {
            this.setHue(object.get("h").getAsFloat());
        }
        if (object.has("s")) {
            this.setSaturation(object.get("s").getAsFloat());
        }
        if (object.has("v")) {
            this.setBrightness(object.get("v").getAsFloat());
        }
    }
}

