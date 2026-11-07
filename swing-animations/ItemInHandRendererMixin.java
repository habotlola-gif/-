package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.SwingAnimations;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={ItemInHandRenderer.class})
public abstract class ItemInHandRendererMixin {
    @Inject(method={"swingArm"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$customSwing(float progress, PoseStack pose, int side, HumanoidArm arm, CallbackInfo ci) {
        SwingAnimations animations = ModuleManager.SWING_ANIMATIONS;
        if (animations.replacesSwing()) {
            animations.applySwing(pose, progress, side);
            ci.cancel();
        }
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F")})
    private float visualmod$noAttackDip(float scale) {
        return ModuleManager.SWING_ANIMATIONS.replacesSwing() ? 1.0f : scale;
    }

    @ModifyVariable(method={"applyItemArmTransform"}, at=@At(value="HEAD"), argsOnly=true, ordinal=0)
    private float visualmod$noEquipDip(float equipProgress) {
        SwingAnimations animations = ModuleManager.SWING_ANIMATIONS;
        return animations.isEnabled() && animations.noEquip.isOn() ? 0.0f : equipProgress;
    }

    @Inject(method={"applyItemArmTransform"}, at={@At(value="TAIL")})
    private void visualmod$handOffset(PoseStack pose, HumanoidArm arm, float equipProgress, CallbackInfo ci) {
        ModuleManager.VIEW_MODEL.apply(pose, arm);
    }

    @Inject(method={"renderPlayerArm"}, at={@At(value="INVOKE", target="Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal=0, shift=At.Shift.AFTER)})
    private void visualmod$emptyHandOffset(PoseStack pose, SubmitNodeCollector collector, int light, float equipProgress, float swingProgress, HumanoidArm arm, CallbackInfo ci) {
        ModuleManager.VIEW_MODEL.apply(pose, arm);
    }
}

