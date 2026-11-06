package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.AmbienceRenderer;
import com.visualmod.render.ShaderHandsRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(value={GameRenderer.class})
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Inject(method={"renderItemInHand"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal=0, shift=At.Shift.AFTER)})
    private void visualmod$beginHands(float partialTick, boolean sleeping, Matrix4f view, CallbackInfo ci) {
        ShaderHandsRenderer.begin();
    }

    @Inject(method={"renderLevel"}, at={@At(value="INVOKE", target="Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V")})
    private void visualmod$wetReflections(DeltaTracker deltaTracker, CallbackInfo ci) {
        AmbienceRenderer.renderWetReflections();
    }

    @Inject(method={"render"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V", shift=At.Shift.AFTER)})
    private void visualmod$rainDroplets(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        AmbienceRenderer.renderDroplets();
    }

    @Inject(method={"render"}, at={@At(value="HEAD")})
    private void visualmod$aimAssist(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) {
            if (ModuleManager.KILL_AURA.isEnabled()) {
                ModuleManager.KILL_AURA.onRenderFrame(Minecraft.getInstance());
            } else {
                ModuleManager.AIM_ASSIST.onRenderFrame(Minecraft.getInstance());
            }
        }
    }

    @Inject(method={"bobHurt"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$removeHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (ModuleManager.REMOVALS.removes(ModuleManager.REMOVALS.hurtCam)) {
            ci.cancel();
        }
    }

    @ModifyExpressionValue(method={"renderLevel"}, at={@At(value="INVOKE", target="Ljava/lang/Math;max(FF)F", ordinal=0)})
    private float visualmod$removeNauseaWobble(float strength) {
        return ModuleManager.REMOVALS.removes(ModuleManager.REMOVALS.nausea) ? 0.0f : strength;
    }

    @ModifyArg(method={"getProjectionMatrix"}, at={@At(value="INVOKE", target="Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;")}, index=1)
    private float visualmod$aspectRatio(float aspect) {
        return ModuleManager.ASPECT_RATIO.aspect(aspect);
    }

    @ModifyArgs(method={"renderLevel"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/renderer/CachedPerspectiveProjectionMatrixBuffer;getBuffer(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;")})
    private void visualmod$aspectRatioHands(Args args) {
        if (ModuleManager.ASPECT_RATIO.isEnabled()) {
            int height = (Integer)args.get(1);
            args.set(0, (Object)Math.max(1, Math.round((float)height * ModuleManager.ASPECT_RATIO.ratio.getFloat())));
        }
    }

    @Inject(method={"renderItemInHand"}, at={@At(value="TAIL")})
    private void visualmod$endHands(float partialTick, boolean sleeping, Matrix4f view, CallbackInfo ci) {
        if (ShaderHandsRenderer.isRedirecting()) {
            this.featureRenderDispatcher.renderAllFeatures();
            this.renderBuffers.bufferSource().endBatch();
            ShaderHandsRenderer.end();
        }
    }
}

