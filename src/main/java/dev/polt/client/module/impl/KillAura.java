package dev.polt.client.module.impl;

import dev.polt.client.module.Module;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

public class KillAura extends Module {
    private static final double RANGE = 4.2;

    public KillAura(int key) { super("KillAura", key); }

    @Override
    public void onTick() {
        var p = mc.player;
        LivingEntity target = null;
        double best = RANGE * RANGE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity other) || other == p || !other.isAlive()) continue;
            double d = p.squaredDistanceTo(other);
            if (d < best) { best = d; target = other; }
        }
        if (target == null) return;

        // turn towards the target
        Vec3d eye = p.getEyePos();
        Vec3d to = target.getBoundingBox().getCenter().subtract(eye);
        double flat = Math.hypot(to.x, to.z);
        p.setYaw((float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(to.y, flat)));

        // attack only when the cooldown is full
        if (p.getAttackCooldownProgress(0.5f) >= 1.0f) {
            mc.interactionManager.attackEntity(p, target);
            p.swingHand(Hand.MAIN_HAND);
        }
    }
}
