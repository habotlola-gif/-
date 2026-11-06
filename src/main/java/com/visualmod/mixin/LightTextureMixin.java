package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value={LightTexture.class})
public abstract class LightTextureMixin {
    private static final String PUT_FLOAT = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;";

    @ModifyExpressionValue(method={"updateLightTexture"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F")})
    private float visualmod$removeDarknessPulse(float factor) {
        return ModuleManager.REMOVALS.removes(ModuleManager.REMOVALS.blindness) ? 0.0f : factor;
    }

    @ModifyArg(method={"updateLightTexture"}, at={@At(value="INVOKE", target="Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal=3)})
    private float visualmod$nightVision(float value) {
        return ModuleManager.FULLBRIGHT.isEnabled() ? Math.max(value, ModuleManager.FULLBRIGHT.strengthFactor()) : value;
    }

    @ModifyArg(method={"updateLightTexture"}, at={@At(value="INVOKE", target="Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal=6)})
    private float visualmod$brightness(float value) {
        return ModuleManager.FULLBRIGHT.isEnabled() ? Math.max(value, ModuleManager.FULLBRIGHT.strengthFactor()) : value;
    }
}

