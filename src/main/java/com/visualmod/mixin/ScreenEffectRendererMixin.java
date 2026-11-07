package com.visualmod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.Removals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={ScreenEffectRenderer.class})
public abstract class ScreenEffectRendererMixin {
    @Inject(method={"renderFire"}, at={@At(value="HEAD")}, cancellable=true)
    private static void visualmod$removeFire(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (ModuleManager.REMOVALS.removesFire()) {
            ci.cancel();
        }
    }

    @ModifyArg(method={"renderFire"}, at=@At(value="INVOKE", target="Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"), index=1)
    private static float visualmod$lowerFire(float y) {
        return y + ModuleManager.REMOVALS.fireOffset();
    }

    @Inject(method={"renderWater"}, at={@At(value="HEAD")}, cancellable=true)
    private static void visualmod$removeWater(Minecraft minecraft, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        Removals removals = ModuleManager.REMOVALS;
        if (removals.removes(removals.fluidOverlay)) {
            ci.cancel();
        }
    }

    @Inject(method={"renderTex"}, at={@At(value="HEAD")}, cancellable=true)
    private static void visualmod$removeInWall(TextureAtlasSprite sprite, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        Removals removals = ModuleManager.REMOVALS;
        if (removals.removes(removals.fluidOverlay)) {
            ci.cancel();
        }
    }

    @Inject(method={"renderItemActivationAnimation"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$removeTotem(PoseStack poseStack, float partialTick, SubmitNodeCollector collector, CallbackInfo ci) {
        Removals removals = ModuleManager.REMOVALS;
        if (removals.removes(removals.totem)) {
            ci.cancel();
        }
    }
}

