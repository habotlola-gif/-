package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={LivingEntity.class})
public abstract class LivingEntityMixin {
    @ModifyReturnValue(method={"getCurrentSwingDuration"}, at={@At(value="RETURN")})
    private int visualmod$swingSpeed(int duration) {
        if (this instanceof LocalPlayer) {
            return ModuleManager.SWING_ANIMATIONS.adjustDuration(duration);
        }
        return duration;
    }
}

