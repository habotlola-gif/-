package com.visualmod.mixin;

import com.visualmod.module.KillAura;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Коррекция движения для silent-поворота.
 * Физика тика идёт по silent-повороту, поэтому клавиши W/A/S/D пересчитываются в ближайшую из 8 комбинаций
 * так, чтобы ты шёл туда, куда смотрит камера. Пересобираются и keyPresses (их видит сервер), и moveVector,
 * поэтому предсказание движения на сервере остаётся согласованным.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputSilentMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void visualmod$moveFix(CallbackInfo ci) {
        KillAura aura = ModuleManager.KILL_AURA;
        if (!aura.movementFixActive()) return;

        Input k = this.keyPresses;
        int fwd = (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0);
        int left = (k.left() ? 1 : 0) - (k.right() ? 1 : 0);
        if (fwd == 0 && left == 0) return;

        // желаемое направление (в системе камеры) -> в систему silent-поворота
        double th = Math.toRadians(aura.yawDelta());
        double cos = Math.cos(th), sin = Math.sin(th);
        double wantX = left * cos - fwd * sin;
        double wantZ = fwd * cos + left * sin;

        int bestX = 0, bestZ = 0;
        double bestDot = -Double.MAX_VALUE;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                double dot = (x * wantX + z * wantZ) / Math.sqrt(x * x + z * z);
                if (dot > bestDot) { bestDot = dot; bestX = x; bestZ = z; }
            }
        }

        this.keyPresses = new Input(bestZ > 0, bestZ < 0, bestX > 0, bestX < 0,
                k.jump(), k.shift(), k.sprint() && bestZ > 0);
        this.moveVector = new Vec2(bestX, bestZ).normalized();
    }
}
