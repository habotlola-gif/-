package com.visualmod.module;

import com.visualmod.FriendManager;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * KillAura v2 (замена старого класса, тот же публичный интерфейс).
 *
 * Что исправлено относительно v1:
 *  - целимся не в центр хитбокса, а в ближайшую к текущему прицелу точку внутри него
 *    (часто доворачивать вообще не нужно, нет «болтанки» вокруг центра);
 *  - упреждение по скорости цели (predict), поэтому не отстаём от бегущего игрока;
 *  - удар проверяется своим лучом по хитбоксу, а не через client.hitResult (у него дальность 3.0
 *    и он ломается об стену/другую сущность);
 *  - удар проверяется ещё и по повороту, который сервер получил в ПРОШЛОМ тике: пакет атаки
 *    приходит раньше пакета с новым поворотом, из-за этого раньше сервер видел «промах»;
 *  - убрано ожидание 2 «стабильных» тиков, удар сразу как только условия выполнены;
 *  - залипание на цели (hysteresis), чтобы не прыгать между двумя игроками;
 *  - тайминг крита: в воздухе при подъёме ждём падения (до 7 тиков).
 */
public final class KillAura extends Module {
    public final BooleanSetting players = this.setting(new BooleanSetting("players", "Игроки", true));
    public final BooleanSetting monsters = this.setting(new BooleanSetting("monsters", "Монстры", true));
    public final BooleanSetting animals = this.setting(new BooleanSetting("animals", "Животные", false));
    public final NumberSetting range = this.setting(new NumberSetting("range", "Дальность", 3.0, 2.0, 4.5, 0.05, " бл."));
    public final NumberSetting fov = this.setting(new NumberSetting("fov", "Зона захвата", 120.0, 10.0, 360.0, 5.0, "°"));
    public final NumberSetting smoothness = this.setting(new NumberSetting("smoothness", "Плавность", 2.0, 1.0, 10.0, 1.0, ""));
    public final NumberSetting maxSpeed = this.setting(new NumberSetting("max_speed", "Макс. скорость", 540.0, 20.0, 1080.0, 10.0, "°/с"));
    public final NumberSetting predict = this.setting(new NumberSetting("predict", "Упреждение", 1.5, 0.0, 4.0, 0.25, " т."));
    public final BooleanSetting pitch = this.setting(new BooleanSetting("pitch", "По вертикали", true));
    public final BooleanSetting crits = this.setting(new BooleanSetting("crits", "Тайминг крита", true));
    public final BooleanSetting weaponOnly = this.setting(new BooleanSetting("weapon_only", "Только с оружием", true));
    public final BooleanSetting skipAllies = this.setting(new BooleanSetting("skip_allies", "Не бить союзников", true));
    public final BooleanSetting resetSprint = this.setting(new BooleanSetting("reset_sprint", "Сброс спринта", true));
    public final SprintResetter sprintReset = new SprintResetter();

    private static final int MAX_CRIT_WAIT_TICKS = 7;
    private static final double AIM_MARGIN = 0.10; // отступ от краёв хитбокса при наведении

    private long lastFrameNanos = -1L;
    private Entity target;
    private Entity lockedTarget;
    private float sentYaw, sentPitch;     // поворот, ушедший на сервер в прошлом тике
    private boolean hasSent;
    private int critWait;

    public KillAura() {
        super("KillAura", "Плавное наведение и удар по цели", Category.COMBAT);
    }

    // ---------- наведение (каждый кадр) ----------

    public void onRenderFrame(Minecraft client) {
        if (!this.isEnabled()) {
            this.lastFrameNanos = -1L;
            this.target = null;
            return;
        }
        long now = System.nanoTime();
        if (this.lastFrameNanos < 0L) {
            this.lastFrameNanos = now;
            return;
        }
        float dt = (float) Math.min(0.1, (now - this.lastFrameNanos) / 1.0E9);
        this.lastFrameNanos = now;

        LocalPlayer player = client.player;
        if (!canWork(client, player)) {
            this.target = null;
            return;
        }
        this.target = this.findTarget(client, player);
        if (this.target == null) return;

        Vec3 aim = aimPoint(player, this.target);
        Vec3 eye = player.getEyePosition();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        float ease = 1.0f - (float) Math.exp(-30.0f / this.smoothness.getFloat() * dt);
        float maxStep = this.maxSpeed.getFloat() * dt;

        float yaw = player.getYRot();
        float newYaw = yaw + Mth.clamp(Mth.wrapDegrees(wantYaw - yaw) * ease, -maxStep, maxStep);
        player.setYRot(newYaw);
        player.yRotO = newYaw;
        if (this.pitch.get()) {
            float p = player.getXRot();
            float newPitch = Mth.clamp(p + Mth.clamp((wantPitch - p) * ease, -maxStep, maxStep), -90.0f, 90.0f);
            player.setXRot(newPitch);
            player.xRotO = newPitch;
        }
    }

    // ---------- удар (каждый тик, до отправки пакетов движения) ----------

    public void onHandleKeybinds(Minecraft client) {
        if (!this.isEnabled()) return;
        LocalPlayer player = client.player;
        if (!canWork(client, player) || client.gameMode == null
                || client.options.keyAttack.isDown()
                || this.target == null) {
            reset();
            return;
        }
        Entity t = this.target;
        boolean ok = this.isValidTarget(player, t)
                && player.hasLineOfSight(t)
                && player.getAttackStrengthScale(0.0f) >= 0.99f
                && !(this.weaponOnly.get() && !isWeapon(player.getMainHandItem()))
                && inReach(player, t)
                && aimedAt(player, t, player.getYRot(), player.getXRot())
                // сервер при атаке ещё знает поворот из прошлого тика
                && (!this.hasSent || aimedAt(player, t, this.sentYaw, this.sentPitch));
        if (ok && this.crits.get() && !critReady(player)) {
            ok = false;
        }
        // поворот, который уйдёт на сервер в ЭТОМ тике (кадров между handleKeybinds и тиком игрока нет)
        this.sentYaw = player.getYRot();
        this.sentPitch = player.getXRot();
        this.hasSent = true;
        if (!ok) return;

        boolean wasSprinting = player.isSprinting();
        client.gameMode.attack(player, t);
        player.swing(InteractionHand.MAIN_HAND);
        ModuleManager.actedThisTick = true;
        this.critWait = 0;
        if (this.resetSprint.get() && wasSprinting) {
            this.sprintReset.start(2, false);
        }
    }

    @Override
    public void onTick(Minecraft client) {
        this.sprintReset.tick();
    }

    // ---------- геометрия ----------

    /** Точка хитбокса, ближайшая к текущему лучу взгляда, со сдвигом на скорость цели. */
    private Vec3 aimPoint(LocalPlayer player, Entity e) {
        Vec3 eye = player.getEyePosition();
        Vec3 vel = new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
        Vec3 lead = vel.scale(this.predict.get());
        AABB box = e.getBoundingBox().move(lead);
        AABB aimBox = new AABB(
                box.minX + AIM_MARGIN, box.minY + (box.maxY - box.minY) * 0.15, box.minZ + AIM_MARGIN,
                box.maxX - AIM_MARGIN, box.maxY - (box.maxY - box.minY) * 0.08, box.maxZ - AIM_MARGIN);
        Vec3 center = aimBox.getCenter();
        double depth = Math.max(0.5, eye.distanceTo(center));
        Vec3 look = viewVector(player.getYRot(), player.getXRot());
        Vec3 onRay = eye.add(look.scale(depth));
        return new Vec3(
                Mth.clamp(onRay.x, aimBox.minX, aimBox.maxX),
                Mth.clamp(onRay.y, aimBox.minY, aimBox.maxY),
                Mth.clamp(onRay.z, aimBox.minZ, aimBox.maxZ));
    }

    private static Vec3 viewVector(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new Vec3(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
    }

    private static Vec3 nearestPoint(Vec3 from, AABB box) {
        return new Vec3(
                Mth.clamp(from.x, box.minX, box.maxX),
                Mth.clamp(from.y, box.minY, box.maxY),
                Mth.clamp(from.z, box.minZ, box.maxZ));
    }

    /** Дистанция от глаз до ближайшей точки хитбокса (так её считает сервер). */
    private boolean inReach(LocalPlayer player, Entity e) {
        Vec3 eye = player.getEyePosition();
        return eye.distanceTo(nearestPoint(eye, e.getBoundingBox())) <= this.range.get();
    }

    /** Попадает ли луч взгляда с заданным поворотом в хитбокс цели на дистанции range. */
    private boolean aimedAt(LocalPlayer player, Entity e, float yaw, float pitch) {
        Vec3 eye = player.getEyePosition();
        AABB box = e.getBoundingBox();
        if (box.contains(eye)) return true;
        Vec3 end = eye.add(viewVector(yaw, pitch).scale(this.range.get()));
        Optional<Vec3> hit = box.clip(eye, end);
        return hit.isPresent();
    }

    /** Крит: в воздухе при подъёме ждём падения, но не дольше MAX_CRIT_WAIT_TICKS. */
    private boolean critReady(LocalPlayer player) {
        boolean airRising = !player.onGround() && player.getDeltaMovement().y > 0.0
                && !player.isInWater() && !player.onClimbable();
        if (!airRising) return true;
        return ++this.critWait > MAX_CRIT_WAIT_TICKS;
    }

    // ---------- выбор цели ----------

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        double reach = this.range.get();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double maxAngle = this.fov.get() * 0.5;
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        double lockedScore = Double.MAX_VALUE;

        for (Entity e : client.level.getEntities(player, player.getBoundingBox().inflate(reach + 1.0),
                x -> this.isValidTarget(player, x))) {
            AABB box = e.getBoundingBox();
            double dist = eye.distanceTo(nearestPoint(eye, box));
            if (dist > reach) continue;
            Vec3 dir = box.getCenter().subtract(eye);
            double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir.normalize()), -1.0, 1.0)));
            if (angle > maxAngle || !player.hasLineOfSight(e)) continue;
            double score = angle + dist * 6.0;
            if (e == this.lockedTarget) lockedScore = score;
            if (score < bestScore) { bestScore = score; best = e; }
        }
        // не переключаемся на новую цель, пока она не заметно лучше текущей
        if (this.lockedTarget != null && lockedScore < Double.MAX_VALUE && bestScore > lockedScore * 0.7 - 5.0) {
            best = this.lockedTarget;
        }
        this.lockedTarget = best;
        return best;
    }

    private boolean isValidTarget(LocalPlayer player, Entity entity) {
        if (entity == player || !(entity instanceof LivingEntity living) || !living.isAlive()
                || entity instanceof ArmorStand
                || (this.skipAllies.get() && player.isAlliedTo(entity))) {
            return false;
        }
        if (entity instanceof Player other) {
            return this.players.get() && !other.isSpectator()
                    && !FriendManager.isFriend(other.getGameProfile().name());
        }
        return entity.getType().getCategory() == MobCategory.MONSTER ? this.monsters.get() : this.animals.get();
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
                || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
    }

    private boolean canWork(Minecraft client, LocalPlayer player) {
        return player != null && client.level != null && client.screen == null
                && player.isAlive() && !player.isUsingItem();
    }

    private void reset() {
        this.critWait = 0;
        this.hasSent = false;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.lastFrameNanos = -1L;
        this.target = null;
        this.lockedTarget = null;
        this.hasSent = false;
        this.critWait = 0;
        this.sprintReset.cancel();
    }
}
