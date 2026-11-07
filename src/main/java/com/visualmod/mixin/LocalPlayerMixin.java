package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={LocalPlayer.class})
public abstract class LocalPlayerMixin {
    @ModifyExpressionValue(method={"aiStep"}, at={@At(value="INVOKE", target="Lnet/minecraft/world/entity/player/Input;sprint()Z")})
    private boolean visualmod$autoSprint(boolean pressed) {
        return !ModuleManager.CLICK_ACTIONS.pauseMovementInput() && !ModuleManager.AUTO_SWAP.pauseMovementInput() && (pressed || ModuleManager.SPRINT.isEnabled());
    }

    @Inject(method={"tick"}, at={@At(value="HEAD")})
    private void visualmod$beforeClickActionsTick(CallbackInfo ci) {
        ModuleManager.CLICK_ACTIONS.beforePlayerTick((LocalPlayer)(Object)this);
        ModuleManager.AUTO_SWAP.beforePlayerTick((LocalPlayer)(Object)this);
        ModuleManager.AIR_STUCK.beforePlayerTick((LocalPlayer)(Object)this);
    }

    @Inject(method={"tick"}, at={@At(value="TAIL")})
    private void visualmod$afterClickActionsTick(CallbackInfo ci) {
        ModuleManager.CLICK_ACTIONS.afterPlayerTick();
        ModuleManager.AUTO_SWAP.afterPlayerTick();
        ModuleManager.AIR_STUCK.afterPlayerTick((LocalPlayer)(Object)this);
    }
}

