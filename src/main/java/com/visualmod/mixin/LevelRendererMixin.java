package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.ShaderEspRenderer;
import com.visualmod.render.WorldProjection;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={LevelRenderer.class})
public abstract class LevelRendererMixin {
    @Shadow
    private @Nullable RenderTarget entityOutlineTarget;

    @Shadow
    protected abstract boolean shouldShowEntityOutlines();

    @Inject(method={"renderLevel"}, at={@At(value="HEAD")})
    private void visualmod$captureCamera(CallbackInfo ci, @Local(argsOnly=true) Camera camera, @Local(argsOnly=true, ordinal=0) Matrix4f view, @Local(argsOnly=true, ordinal=1) Matrix4f projection) {
        WorldProjection.capture(view, projection, camera.position());
    }

    @ModifyExpressionValue(method={"extractVisibleEntities"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z")})
    private boolean visualmod$renderHiddenEspEntities(boolean visible, @Local Entity entity) {
        return visible || ModuleManager.SHADER_ESP.shouldHighlight(entity);
    }

    @WrapWithCondition(method={"renderLevel"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/renderer/PostChain;addToFrame(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;IILnet/minecraft/client/renderer/PostChain$TargetBundle;)V", ordinal=0)})
    private boolean visualmod$skipVanillaOutline(PostChain chain, FrameGraphBuilder builder, int width, int height, PostChain.TargetBundle targets) {
        return !ShaderEspRenderer.isActive();
    }

    @Inject(method={"doEntityOutline"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$shaderEsp(CallbackInfo ci) {
        if (ShaderEspRenderer.isActive()) {
            if (this.shouldShowEntityOutlines() && this.entityOutlineTarget != null) {
                ShaderEspRenderer.composite(this.entityOutlineTarget);
            }
            ci.cancel();
        }
    }
}

