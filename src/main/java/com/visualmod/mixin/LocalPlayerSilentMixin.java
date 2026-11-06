package com.visualmod.mixin;

import com.visualmod.module.ModuleManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Подставляет silent-поворот на время тика игрока и возвращает камеру после него. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSilentMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void visualmod$silentBegin(CallbackInfo ci) {
        ModuleManager.KILL_AURA.beginPlayerTick((LocalPlayer) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void visualmod$silentEnd(CallbackInfo ci) {
        ModuleManager.KILL_AURA.endPlayerTick((LocalPlayer) (Object) this);
    }
}
