package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={Level.class})
public abstract class LevelMixin {
    @ModifyReturnValue(method={"getDayTime"}, at={@At(value="RETURN")})
    private long visualmod$customTime(long dayTime) {
        if (this instanceof ClientLevel) {
            return ModuleManager.AMBIENCE.adjustDayTime(dayTime);
        }
        return dayTime;
    }

    @ModifyReturnValue(method={"getRainLevel"}, at={@At(value="RETURN")})
    private float visualmod$customRain(float rainLevel) {
        if (this instanceof ClientLevel) {
            return ModuleManager.AMBIENCE.adjustRainLevel(rainLevel);
        }
        return rainLevel;
    }
}

