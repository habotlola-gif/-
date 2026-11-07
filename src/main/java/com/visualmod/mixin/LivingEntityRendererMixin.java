package com.visualmod.mixin;

import com.visualmod.module.KillAura;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * В третьем лице модель игрока должна смотреть туда же, куда silent-поворот KillAura (как видят остальные),
 * а не за камерой. Камеру и первое лицо это не трогает: меняется только состояние отрисовки модели.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void visualmod$silentModel(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof LocalPlayer)) return;
        KillAura aura = ModuleManager.KILL_AURA;
        if (!aura.hasSilentAim()) return;
        state.xRot = aura.renderPitch(partialTick);
        state.yRot = Mth.wrapDegrees(aura.renderYaw(partialTick) - state.bodyRot);
    }
}
