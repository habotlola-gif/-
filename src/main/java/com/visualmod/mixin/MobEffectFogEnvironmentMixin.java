package com.visualmod.mixin;

import com.visualmod.module.ModuleManager;
import com.visualmod.module.Removals;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={MobEffectFogEnvironment.class})
public abstract class MobEffectFogEnvironmentMixin {
    @Inject(method={"isApplicable"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$removeEffectFog(@Nullable FogType fogType, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        Removals removals = ModuleManager.REMOVALS;
        Object self = this;
        if (removals.removes(removals.blindness) && (self instanceof BlindnessFogEnvironment || self instanceof DarknessFogEnvironment)) {
            cir.setReturnValue(false);
        }
    }
}

