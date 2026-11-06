package com.visualmod.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.visualmod.module.Ambience;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.AmbienceRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={FogRenderer.class})
public abstract class FogRendererMixin {
    @Inject(method={"setupFog"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F", opcode=181, shift=At.Shift.AFTER)})
    private void visualmod$atmosphericFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level, CallbackInfoReturnable<Vector4f> cir, @Local FogData fogData, @Local Vector4f color) {
        Ambience ambience = ModuleManager.AMBIENCE;
        if (!ambience.overridesFog() || camera.getFluidInCamera() != FogType.NONE) {
            AmbienceRenderer.setHorizonColor(color);
            return;
        }
        float end = ambience.fogDistance.getFloat();
        fogData.environmentalStart = Math.min(fogData.environmentalStart, end * 0.1f);
        fogData.environmentalEnd = Math.min(fogData.environmentalEnd, end);
        fogData.skyEnd = Math.min(fogData.skyEnd, end);
        fogData.cloudEnd = Math.min(fogData.cloudEnd, end * 1.5f);
        ambience.adjustFogColor(color);
        AmbienceRenderer.setHorizonColor(color);
    }
}

