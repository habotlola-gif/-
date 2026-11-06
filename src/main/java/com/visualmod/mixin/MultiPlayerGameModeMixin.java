package com.visualmod.mixin;

import com.visualmod.module.ModuleManager;
import com.visualmod.render.TargetTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={MultiPlayerGameMode.class})
public abstract class MultiPlayerGameModeMixin {
    @Inject(method={"attack"}, at={@At(value="HEAD")})
    private void visualmod$autoMace(Player player, Entity target, CallbackInfo ci) {
        ModuleManager.AUTO_MACE.onAttack(player, target);
        TargetTracker.onAttack(target);
    }
}

