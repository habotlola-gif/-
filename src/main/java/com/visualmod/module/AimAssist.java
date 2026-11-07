package com.visualmod.module;

import com.visualmod.FriendManager;
import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AimAssist
extends Module {
    public final BooleanSetting players = this.setting(new BooleanSetting("players", "\u0418\u0433\u0440\u043e\u043a\u0438", true));
    public final BooleanSetting monsters = this.setting(new BooleanSetting("monsters", "\u041c\u043e\u043d\u0441\u0442\u0440\u044b", true));
    public final BooleanSetting animals = this.setting(new BooleanSetting("animals", "\u0416\u0438\u0432\u043e\u0442\u043d\u044b\u0435", false));
    public final NumberSetting range = this.setting(new NumberSetting("range", "\u0414\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u044c", 4.0, 2.0, 6.0, 0.25, " \u0431\u043b."));
    public final NumberSetting fov = this.setting(new NumberSetting("fov", "\u0417\u043e\u043d\u0430 \u0437\u0430\u0445\u0432\u0430\u0442\u0430", 60.0, 10.0, 180.0, 5.0, "\u00b0"));
    public final NumberSetting smoothness = this.setting(new NumberSetting("smoothness", "\u041f\u043b\u0430\u0432\u043d\u043e\u0441\u0442\u044c", 6.0, 1.0, 10.0, 1.0, ""));
    public final NumberSetting maxSpeed = this.setting(new NumberSetting("max_speed", "\u041c\u0430\u043a\u0441. \u0441\u043a\u043e\u0440\u043e\u0441\u0442\u044c", 240.0, 60.0, 720.0, 20.0, "\u00b0/\u0441"));
    public final BooleanSetting pitch = this.setting(new BooleanSetting("pitch", "\u041f\u043e \u0432\u0435\u0440\u0442\u0438\u043a\u0430\u043b\u0438", true));
    public final BooleanSetting onlyAttacking = this.setting(new BooleanSetting("only_attacking", "\u0422\u043e\u043b\u044c\u043a\u043e \u043f\u0440\u0438 \u0430\u0442\u0430\u043a\u0435", false));
    public final BooleanSetting throughWalls = this.setting(new BooleanSetting("through_walls", "\u0421\u043a\u0432\u043e\u0437\u044c \u0441\u0442\u0435\u043d\u044b", false));
    public final BooleanSetting skipAllies = this.setting(new BooleanSetting("skip_allies", "\u041d\u0435 \u043d\u0430\u0432\u043e\u0434\u0438\u0442\u044c\u0441\u044f \u043d\u0430 \u0441\u043e\u044e\u0437\u043d\u0438\u043a\u043e\u0432", true));
    private long lastFrameNanos = -1L;

    public AimAssist() {
        super("AimAssist", "\u041f\u043b\u0430\u0432\u043d\u044b\u0439 \u0434\u043e\u0432\u043e\u0440\u043e\u0442 \u043f\u0440\u0438\u0446\u0435\u043b\u0430 \u043a \u0446\u0435\u043b\u0438", Category.COMBAT);
    }

    public void onRenderFrame(Minecraft client) {
        if (!this.isEnabled()) {
            this.lastFrameNanos = -1L;
            return;
        }
        long now = System.nanoTime();
        if (this.lastFrameNanos < 0L) {
            this.lastFrameNanos = now;
            return;
        }
        float dt = (float)Math.min(0.1, (double)(now - this.lastFrameNanos) / 1.0E9);
        this.lastFrameNanos = now;
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.screen != null || !player.isAlive() || player.isUsingItem()) {
            return;
        }
        if (this.onlyAttacking.isOn() && !client.options.keyAttack.isDown()) {
            return;
        }
        Entity target = this.findTarget(client, player);
        if (target == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double dx = (box.minX + box.maxX) * 0.5 - eye.x;
        double dy = (box.minY + box.maxY) * 0.5 - eye.y;
        double dz = (box.minZ + box.maxZ) * 0.5 - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontal)));
        float rate = 18.0f / this.smoothness.getFloat();
        float ease = 1.0f - (float)Math.exp(-rate * dt);
        float maxStep = this.maxSpeed.getFloat() * dt;
        float deltaYaw = Mth.wrapDegrees((float)(wantYaw - player.getYRot()));
        float stepYaw = Mth.clamp((float)(deltaYaw * ease), (float)(-maxStep), (float)maxStep);
        float newYaw = player.getYRot() + stepYaw;
        player.setYRot(newYaw);
        player.yRotO = newYaw;
        if (this.pitch.isOn()) {
            float deltaPitch = wantPitch - player.getXRot();
            float stepPitch = Mth.clamp((float)(deltaPitch * ease), (float)(-maxStep), (float)maxStep);
            float newPitch = Mth.clamp((float)(player.getXRot() + stepPitch), (float)-90.0f, (float)90.0f);
            player.setXRot(newPitch);
            player.xRotO = newPitch;
        }
    }

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        double reach = this.range.get();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double maxAngle = this.fov.get() * 0.5;
        AABB area = player.getBoundingBox().inflate(reach);
        Entity best = null;
        double bestAngle = Double.MAX_VALUE;
        for (Entity entity : client.level.getEntities((Entity)player, area, e -> this.isValidTarget(player, (Entity)e))) {
            double angle;
            AABB box = entity.getBoundingBox();
            double cx = (box.minX + box.maxX) * 0.5;
            double cy = (box.minY + box.maxY) * 0.5;
            double cz = (box.minZ + box.maxZ) * 0.5;
            Vec3 dir = new Vec3(cx - eye.x, cy - eye.y, cz - eye.z);
            if (dir.length() > reach || (angle = Math.toDegrees(Math.acos(Mth.clamp((double)look.dot(dir.normalize()), (double)-1.0, (double)1.0)))) > maxAngle || angle >= bestAngle || !this.throughWalls.isOn() && !player.hasLineOfSight(entity)) continue;
            best = entity;
            bestAngle = angle;
        }
        return best;
    }

    private boolean isValidTarget(LocalPlayer player, Entity target) {
        LivingEntity living;
        if (target == player || !(target instanceof LivingEntity) || !(living = (LivingEntity)target).isAlive() || target instanceof ArmorStand) {
            return false;
        }
        if (this.skipAllies.isOn() && player.isAlliedTo(target)) {
            return false;
        }
        if (target instanceof Player) {
            Player other = (Player)target;
            if (FriendManager.isFriend(other.getGameProfile().name())) {
                return false;
            }
            return this.players.isOn() && !target.isSpectator();
        }
        return target.getType().getCategory() == MobCategory.MONSTER ? this.monsters.isOn() : this.animals.isOn();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.lastFrameNanos = -1L;
    }
}

