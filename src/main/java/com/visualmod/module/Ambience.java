package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ColorSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector4f;

public class Ambience
extends Module {
    public static final int WEATHER_VANILLA = 0;
    public static final int WEATHER_CLEAR = 1;
    public static final int WEATHER_RAIN = 2;
    public static final int WEATHER_SNOW = 3;
    public final BooleanSetting fog = this.setting(new BooleanSetting("fog", "\u0422\u0443\u043c\u0430\u043d", true));
    public final NumberSetting fogDistance = this.setting(new NumberSetting("fog_distance", "\u0414\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u044c \u0442\u0443\u043c\u0430\u043d\u0430", 64.0, 16.0, 256.0, 4.0, " \u0431\u043b."));
    public final ModeSetting fogColorMode = this.setting(new ModeSetting("fog_color_mode", "\u0426\u0432\u0435\u0442 \u0442\u0443\u043c\u0430\u043d\u0430", "\u041a\u0430\u043a \u0432 \u0438\u0433\u0440\u0435", "\u0421\u0432\u043e\u0439"));
    public final ColorSetting fogColor = this.setting(new ColorSetting("fog_color", "\u041e\u0442\u0442\u0435\u043d\u043e\u043a \u0442\u0443\u043c\u0430\u043d\u0430", 215.0f));
    public final BooleanSetting customTime = this.setting(new BooleanSetting("custom_time", "\u0421\u0432\u043e\u0451 \u0432\u0440\u0435\u043c\u044f", false));
    public final NumberSetting time = this.setting(new NumberSetting("time", "\u0412\u0440\u0435\u043c\u044f", 18.0, 0.0, 24.0, 0.5, " \u0447"));
    public final ModeSetting weather = this.setting(new ModeSetting("weather", "\u041f\u043e\u0433\u043e\u0434\u0430", "\u041a\u0430\u043a \u0432 \u0438\u0433\u0440\u0435", "\u042f\u0441\u043d\u043e", "\u0414\u043e\u0436\u0434\u044c", "\u0421\u043d\u0435\u0433"));
    public final BooleanSetting softRain = this.setting(new BooleanSetting("soft_rain", "\u041a\u0440\u0430\u0441\u0438\u0432\u044b\u0439 \u0434\u043e\u0436\u0434\u044c", true));
    public final ModeSetting wetGround = this.setting(new ModeSetting("wet_ground", "\u041c\u043e\u043a\u0440\u0430\u044f \u0437\u0435\u043c\u043b\u044f", "\u0412 \u0434\u043e\u0436\u0434\u044c", "\u0412\u0441\u0435\u0433\u0434\u0430", "\u0412\u044b\u043a\u043b"));
    public final NumberSetting puddles = this.setting(new NumberSetting("puddles", "\u041b\u0443\u0436\u0438", 55.0, 0.0, 100.0, 5.0, "%"));
    public final NumberSetting reflections = this.setting(new NumberSetting("reflections", "\u041e\u0442\u0440\u0430\u0436\u0435\u043d\u0438\u044f", 85.0, 10.0, 100.0, 5.0, "%"));
    public final BooleanSetting droplets = this.setting(new BooleanSetting("droplets", "\u041a\u0430\u043f\u043b\u0438 \u043d\u0430 \u044d\u043a\u0440\u0430\u043d\u0435", false));
    public final NumberSetting dropletStrength = this.setting(new NumberSetting("droplet_strength", "\u0421\u0438\u043b\u0430 \u043a\u0430\u043f\u0435\u043b\u044c", 70.0, 10.0, 100.0, 5.0, "%"));
    private float rainBlend = -1.0f;
    private float dropletBlend;
    private float wetness;

    public Ambience() {
        super("Ambience", "\u0422\u0443\u043c\u0430\u043d, \u0432\u0440\u0435\u043c\u044f, \u043f\u043e\u0433\u043e\u0434\u0430 \u0438 \u043c\u043e\u043a\u0440\u0430\u044f \u0437\u0435\u043c\u043b\u044f", Category.VISUALS);
    }

    public long adjustDayTime(long dayTime) {
        if (!this.isEnabled() || !this.customTime.isOn()) {
            return dayTime;
        }
        long day = Math.floorDiv(dayTime, 24000L);
        long ticks = Math.floorMod(Math.round((this.time.get() - 6.0) * 1000.0), 24000L);
        return day * 24000L + ticks;
    }

    public boolean usesSoftRain() {
        return this.isEnabled() && this.softRain.isOn();
    }

    public boolean overridesWeather() {
        return this.isEnabled() && this.weather.getIndex() != 0;
    }

    public float adjustRainLevel(float rainLevel) {
        if (!this.overridesWeather() || this.rainBlend < 0.0f) {
            return rainLevel;
        }
        return this.rainBlend;
    }

    public Biome.Precipitation adjustPrecipitation(Biome.Precipitation precipitation) {
        if (!this.overridesWeather()) {
            return precipitation;
        }
        return switch (this.weather.getIndex()) {
            case 2 -> Biome.Precipitation.RAIN;
            case 3 -> Biome.Precipitation.SNOW;
            default -> precipitation;
        };
    }

    @Override
    public void onTick(Minecraft client) {
        float target;
        if (client.level == null) {
            return;
        }
        target = switch (this.weather.getIndex()) {
            case 2, 3 -> 1.0f;
            case 1 -> 0.0f;
            default -> -1.0f;
        };
        if (target < 0.0f) {
            this.rainBlend = -1.0f;
        } else {
            if (this.rainBlend < 0.0f) {
                this.rainBlend = client.level.getRainLevel(1.0f);
            }
            this.rainBlend += Math.signum(target - this.rainBlend) * Math.min(0.025f, Math.abs(target - this.rainBlend));
        }
        float dropletTarget = this.shouldShowDroplets(client) ? 1.0f : 0.0f;
        this.dropletBlend += Math.signum(dropletTarget - this.dropletBlend) * Math.min(dropletTarget > this.dropletBlend ? 0.02f : 0.008f, Math.abs(dropletTarget - this.dropletBlend));
        float wetTarget = switch (this.wetGround.getIndex()) {
            case 0 -> {
                if (this.isRainingAtPlayer(client)) {
                    yield 1.0f;
                }
                yield 0.0f;
            }
            case 1 -> 1.0f;
            default -> 0.0f;
        };
        this.wetness += Math.signum(wetTarget - this.wetness) * Math.min(wetTarget > this.wetness ? 0.004f : 0.0015f, Math.abs(wetTarget - this.wetness));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            this.rainBlend = -1.0f;
            this.dropletBlend = 0.0f;
            this.wetness = 0.0f;
        }
    }

    private boolean isRainingAtPlayer(Minecraft client) {
        if (client.player == null || client.level.getRainLevel(1.0f) < 0.2f) {
            return false;
        }
        BlockPos pos = client.player.blockPosition();
        Biome.Precipitation precipitation = this.adjustPrecipitation(((Biome)client.level.getBiome(pos).value()).getPrecipitationAt(pos, client.level.getSeaLevel()));
        return precipitation == Biome.Precipitation.RAIN;
    }

    public float wetness() {
        return this.isEnabled() ? this.wetness : 0.0f;
    }

    public float rippleStrength(Minecraft client) {
        return this.isRainingAtPlayer(client) ? client.level.getRainLevel(1.0f) : 0.0f;
    }

    private boolean shouldShowDroplets(Minecraft client) {
        LocalPlayer player = client.player;
        if (!this.droplets.isOn() || player == null || player.isEyeInFluid(FluidTags.WATER)) {
            return false;
        }
        if (client.level.getRainLevel(1.0f) < 0.2f) {
            return false;
        }
        BlockPos head = BlockPos.containing((Position)player.getEyePosition());
        if (!client.level.canSeeSky(head)) {
            return false;
        }
        Biome.Precipitation precipitation = this.adjustPrecipitation(((Biome)client.level.getBiome(head).value()).getPrecipitationAt(head, client.level.getSeaLevel()));
        return precipitation == Biome.Precipitation.RAIN;
    }

    public float dropletIntensity() {
        return this.isEnabled() ? this.dropletBlend * this.dropletStrength.getFloat() / 100.0f : 0.0f;
    }

    public boolean overridesFog() {
        return this.isEnabled() && this.fog.isOn();
    }

    public void adjustFogColor(Vector4f color) {
        if (!this.overridesFog() || this.fogColorMode.getIndex() == 0) {
            return;
        }
        int rgb = this.fogColor.rgb();
        float r = (float)(rgb >> 16 & 0xFF) / 255.0f;
        float g = (float)(rgb >> 8 & 0xFF) / 255.0f;
        float b = (float)(rgb & 0xFF) / 255.0f;
        float originalLuma = 0.299f * color.x + 0.587f * color.y + 0.114f * color.z;
        float customLuma = Math.max(0.01f, 0.299f * r + 0.587f * g + 0.114f * b);
        float k = Math.min(1.5f, originalLuma / customLuma);
        color.set(r * k, g * k, b * k, color.w);
    }
}

