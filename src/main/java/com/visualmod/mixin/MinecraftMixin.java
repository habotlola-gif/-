package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.ShaderHandsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={Minecraft.class})
public abstract class MinecraftMixin {
    @ModifyReturnValue(method={"shouldEntityAppearGlowing"}, at={@At(value="RETURN")})
    private boolean visualmod$espGlowing(boolean glowing, Entity entity) {
        return glowing || ModuleManager.SHADER_ESP.shouldHighlight(entity);
    }

    @Inject(method={"handleKeybinds"}, at={@At(value="HEAD")})
    private void visualmod$triggerBot(CallbackInfo ci) {
        ModuleManager.actedThisTick = false;
        if (ModuleManager.KILL_AURA.isEnabled()) {
            ModuleManager.KILL_AURA.onHandleKeybinds((Minecraft)this);
        } else {
            ModuleManager.TRIGGER_BOT.onHandleKeybinds((Minecraft)this);
        }
    }

    @Inject(method={"handleKeybinds"}, at={@At(value="TAIL")})
    private void visualmod$inventoryTail(CallbackInfo ci) {
        Minecraft client = (Minecraft)this;
        ModuleManager.CLICK_ACTIONS.onHandleKeybinds(client);
        ModuleManager.AUTO_SWAP.onHandleKeybinds(client);
        ModuleManager.FT_HELPER.onHandleKeybinds(client);
    }

    @Inject(method={"startAttack"}, at={@At(value="HEAD")})
    private void visualmod$markAttack(CallbackInfoReturnable<Boolean> cir) {
        ModuleManager.actedThisTick = true;
    }

    @Inject(method={"startUseItem"}, at={@At(value="HEAD")})
    private void visualmod$markUse(CallbackInfo ci) {
        ModuleManager.actedThisTick = true;
    }

    @Inject(method={"getMainRenderTarget"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$redirectMainTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = ShaderHandsRenderer.redirectTarget();
        if (target != null) {
            cir.setReturnValue((Object)target);
        }
    }
}

