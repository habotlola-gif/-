package com.visualmod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

public final class TargetTracker {
    private static final long HIT_MEMORY_MS = 3000L;
    private static final double MAX_DISTANCE = 12.0;
    private static @Nullable LivingEntity lastHit;
    private static long lastHitAt;

    private TargetTracker() {
    }

    public static void onAttack(Entity target) {
        if (target instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)target;
            if (!(target instanceof ArmorStand)) {
                lastHit = living;
                lastHitAt = System.currentTimeMillis();
                ParticlesRenderer.onAttack(living);
                StarsEsp.onAttack(living);
            }
        }
    }

    public static @Nullable LivingEntity current() {
        LivingEntity looked;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return null;
        }
        if (lastHit != null && TargetTracker.isValid(player, lastHit) && System.currentTimeMillis() - lastHitAt < 3000L) {
            return lastHit;
        }
        Entity entity = client.crosshairPickEntity;
        if (entity instanceof LivingEntity && !((looked = (LivingEntity)entity) instanceof ArmorStand) && TargetTracker.isValid(player, looked)) {
            return looked;
        }
        return null;
    }

    private static boolean isValid(LocalPlayer player, LivingEntity entity) {
        return entity.isAlive() && entity.level() == player.level() && !entity.isInvisibleTo((Player)player) && (double)player.distanceTo((Entity)entity) <= 12.0;
    }
}

