package com.visualmod.module;

import com.visualmod.FriendManager;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import com.visualmod.multipoint.MultipointPath;
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

/**
 * KillAura v2 — SILENT (серверные повороты).
 *
 * Как это работает (за один клиентский тик N):
 *  1. HEAD LocalPlayer.tick  -> beginPlayerTick(): выбираем цель, считаем silent-поворот, сохраняем
 *     поворот камеры и на время тика подставляем игроку silent-поворот.
 *     Вся физика тика и пакет движения (sendPosition) используют ЭТОТ поворот, поэтому сервер/Grim
 *     видят согласованные поворот + ввод + движение.
 *  2. RETURN LocalPlayer.tick -> endPlayerTick(): возвращаем игроку поворот камеры. Твоя камера не дёргается.
 *  3. Тик N+1, до пакетов движения (handleKeybinds): сервер уже знает silent-поворот из тика N,
 *     поэтому бьём именно здесь — как это делает ванильный клиент (атака идёт до пакета движения).
 *
 * Плюс:
 *  - поворот привязан к сетке чувствительности мыши (GCD), иначе aim-проверки видят «нечеловеческие» повороты;
 *  - коррекция движения (KeyboardInputSilentMixin): клавиши пересчитываются так, чтобы ты шёл
 *    туда, куда смотришь камерой, а не куда смотрит silent-поворот;
 *  - удар только если silent-поворот прошлого тика реально попадает в хитбокс (по лучу), дистанция
 *    считается до ближайшей точки хитбокса (как на сервере).
 */
public final class KillAura extends Module {
    public final BooleanSetting players = this.setting(new BooleanSetting("players", "Игроки", true));
    public final BooleanSetting monsters = this.setting(new BooleanSetting("monsters", "Монстры", true));
    public final BooleanSetting animals = this.setting(new BooleanSetting("animals", "Животные", false));
    public final NumberSetting range = this.setting(new NumberSetting("range", "Дальность", 3.0, 2.0, 4.5, 0.05, " бл."));
    public final NumberSetting fov = this.setting(new NumberSetting("fov", "Зона захвата", 180.0, 10.0, 360.0, 5.0, "°"));
    public final NumberSetting smoothness = this.setting(new NumberSetting("smoothness", "Плавность", 2.0, 1.0, 10.0, 1.0, ""));
    public final BooleanSetting pitch = this.setting(new BooleanSetting("pitch", "По вертикали", true));
    public final BooleanSetting gcd = this.setting(new BooleanSetting("gcd", "Сетка чувствительности", true));
    public final BooleanSetting moveFix = this.setting(new BooleanSetting("move_fix", "Коррекция движения", true));
    public final BooleanSetting crits = this.setting(new BooleanSetting("crits", "Тайминг крита", true));
    public final BooleanSetting onlyCrits = this.setting(new BooleanSetting("only_crits", "Бить только с критом", false));
    public final BooleanSetting rage = this.setting(new BooleanSetting("rage", "Rage (HvH, full target)", false));
    public final BooleanSetting weaponOnly = this.setting(new BooleanSetting("weapon_only", "Только с оружием", true));
    public final BooleanSetting skipAllies = this.setting(new BooleanSetting("skip_allies", "Не бить союзников", true));
    public final BooleanSetting resetSprint = this.setting(new BooleanSetting("reset_sprint", "Сброс спринта", true));
    public final SprintResetter sprintReset = new SprintResetter();

    private static final int MAX_CRIT_WAIT_TICKS = 7;
    private static final double AIM_MARGIN = 0.10;
    /** Цель отпускается, когда уйдёт дальше этого расстояния (между 5 и 7 блоками). */
    private static final double RELEASE_RANGE = 6.0;

    private Entity target;
    private Entity lockedTarget;

    /** true только внутри тика игрока, пока ему подставлен silent-поворот. */
    private boolean active;
    /** известен ли поворот, который сервер получил в прошлом тике (aimYaw/aimPitch). */
    private boolean aimValid;
    private float aimYaw, aimPitch;   // silent-поворот (то, что видит сервер)
    private float camYaw, camPitch;   // реальная камера, сохраняется на время тика
    private int critWait;

    public KillAura() {
        super("KillAura", "Silent-наведение и удар по цели", Category.COMBAT);
    }

    // ------------------------------------------------------------------
    // Хуки из миксинов
    // ------------------------------------------------------------------

    /** Вызывается из GameRendererMixin каждый кадр. В silent-режиме камеру не трогаем. */
    public void onRenderFrame(Minecraft client) {
    }

    /** HEAD LocalPlayer.tick. */
    public void beginPlayerTick(LocalPlayer player) {
        this.active = false;
        if (!this.isEnabled()) {
            this.aimValid = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!canWork(mc, player)) {
            this.aimValid = false;
            this.target = null;
            return;
        }
        this.target = this.findTarget(mc, player);
        if (this.target == null) {
            this.aimValid = false;
            return;
        }

        this.camYaw = player.getYRot();
        this.camPitch = player.getXRot();
        if (!this.aimValid) {
            this.aimYaw = this.camYaw;
            this.aimPitch = this.camPitch;
            this.aimValid = true;
        }

        Vec3 aim = aimPoint(player, this.target);
        Vec3 eye = player.getEyePosition();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        boolean rage = this.rage.isOn();
        float ease = rage ? 1.0f : 1.0f - (float) Math.exp(-30.0f / this.smoothness.getFloat() * 0.05f);
        float maxStep = rage ? 180.0f : ModuleManager.EXPERIMENTAL.speedDegPerSec() * 0.05f;
        float step = this.gcd.isOn() ? gcdStep(mc) : 0.0f;

        float dYaw = Mth.clamp(Mth.wrapDegrees(wantYaw - this.aimYaw) * ease, -maxStep, maxStep);
        float dPitch = this.pitch.isOn()
                ? Mth.clamp((wantPitch - this.aimPitch) * ease, -maxStep, maxStep) : 0.0f;
        this.aimYaw += snap(dYaw, step);
        this.aimPitch = Mth.clamp(this.aimPitch + snap(dPitch, step), -90.0f, 90.0f);

        player.setYRot(this.aimYaw);
        player.setXRot(this.aimPitch);
        player.yRotO = this.aimYaw;
        player.xRotO = this.aimPitch;
        this.active = true;
    }

    /** RETURN LocalPlayer.tick. */
    public void endPlayerTick(LocalPlayer player) {
        if (!this.active) return;
        player.setYRot(this.camYaw);
        player.setXRot(this.camPitch);
        player.yRotO = this.camYaw;
        player.xRotO = this.camPitch;
        this.active = false;
    }

    /** Удар. Вызывается из MinecraftMixin.handleKeybinds — до пакетов движения этого тика. */
    public void onHandleKeybinds(Minecraft client) {
        if (!this.isEnabled()) return;
        LocalPlayer player = client.player;
        if (!canWork(client, player) || client.gameMode == null
                || client.options.keyAttack.isDown() || this.target == null) {
            this.critWait = 0;
            return;
        }
        Entity t = this.target;
        // поворот, который сервер знает с прошлого тика
        float srvYaw = this.aimValid ? this.aimYaw : player.getYRot();
        float srvPitch = this.aimValid ? this.aimPitch : player.getXRot();

        boolean ok = this.isValidTarget(player, t)
                && player.hasLineOfSight(t)
                && player.getAttackStrengthScale(0.0f) >= 0.99f
                && !(this.weaponOnly.isOn() && !isWeapon(player.getMainHandItem()))
                && inReach(player, t)
                && aimedAt(player, t, srvYaw, srvPitch);
        if (ok && this.onlyCrits.isOn()) {
            ok = strictCrit(player);
        } else if (ok && this.crits.isOn() && !this.rage.isOn() && !critReady(player)) {
            ok = false;
        }
        if (!ok) return;

        boolean wasSprinting = player.isSprinting();
        client.gameMode.attack(player, t);
        player.swing(InteractionHand.MAIN_HAND);
        ModuleManager.actedThisTick = true;
        this.critWait = 0;
        if (this.resetSprint.isOn() && wasSprinting) {
            this.sprintReset.start(2, false);
        }
    }

    @Override
    public void onTick(Minecraft client) {
        this.sprintReset.tick();
    }

    // ------------------------------------------------------------------
    // Для коррекции движения (KeyboardInputSilentMixin)
    // ------------------------------------------------------------------

    public boolean movementFixActive() {
        return this.active && this.moveFix.isOn();
    }

    /** Разница «камера − silent» в градусах: на сколько повёрнут ввод относительно взгляда камеры. */
    public float yawDelta() {
        return this.camYaw - this.aimYaw;
    }

    // ------------------------------------------------------------------
    // Геометрия
    // ------------------------------------------------------------------

    /** Шаг сетки чувствительности в градусах: одна «единица» движения мыши. */
    private static float gcdStep(Minecraft mc) {
        double sens = mc.options.sensitivity().get();
        double f = sens * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }

    private static float snap(float delta, float step) {
        return step <= 0.0f ? delta : Math.round(delta / step) * step;
    }

    /**
     * Multipoint: точка прицеливания обходит хитбокс по пути из редактора (config/visualmod-multipoint.json),
     * путь всегда внутри хитбокса, поэтому удар засчитывается из любой его точки.
     * Настройки (скорость, дуга, размер, направление) в модуле Experimental. Плюс упреждение на скорость цели.
     */
    private Vec3 aimPoint(LocalPlayer player, Entity e) {
        Experimental ex = ModuleManager.EXPERIMENTAL;
        Vec3 vel = new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
        AABB box = e.getBoundingBox().move(vel.scale(ex.predictTicks()));
        double hw = (box.maxX - box.minX) * 0.5 - AIM_MARGIN;
        double h = box.maxY - box.minY;
        Vec3 c = box.getCenter();

        float px, py;
        if (ex.multipointOn()) {
            double t = (player.tickCount + (e.getId() % 17) * 3) / 20.0 * ex.mpSpeedValue();
            double phase = switch (ex.mpDirectionIndex()) {
                case 1 -> 1.0 - (t - Math.floor(t));
                case 2 -> { double f = t - Math.floor(t); yield f < 0.5 ? f * 2 : 2 - f * 2; }
                default -> t - Math.floor(t);
            };
            float[] pt = MultipointPath.sample(phase, ex.mpArcValue(), ex.mpSizeValue());
            px = pt[0];
            py = pt[1];
        } else {
            px = 0.0f;
            py = 0.75f;
        }

        // «вправо» относительно взгляда на цель
        Vec3 eye = player.getEyePosition();
        double dx = c.x - eye.x, dz = c.z - eye.z;
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        double rx = -dz / len, rz = dx / len;
        double wx = Mth.clamp(c.x + rx * px * hw, box.minX + AIM_MARGIN, box.maxX - AIM_MARGIN);
        double wz = Mth.clamp(c.z + rz * px * hw, box.minZ + AIM_MARGIN, box.maxZ - AIM_MARGIN);
        return new Vec3(wx, box.minY + h * py, wz);
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

    /** Попадает ли луч заданного поворота в хитбокс цели на дистанции range. */
    private boolean aimedAt(LocalPlayer player, Entity e, float yaw, float pitch) {
        Vec3 eye = player.getEyePosition();
        AABB box = e.getBoundingBox();
        if (box.contains(eye)) return true;
        Vec3 end = eye.add(viewVector(yaw, pitch).scale(this.range.get()));
        return box.clip(eye, end).isPresent();
    }

    /** Крит: в воздухе при подъёме ждём падения, но не дольше MAX_CRIT_WAIT_TICKS. */
    /**
     * Бить только с критом. Условия крита как в игре: падение (fallDistance > 0), не на земле, не в воде,
     * не на лестнице, не пассажир и НЕ в спринте. Если падаем в спринте, на тик отпускаем спринт
     * (W-tap) и бьём на следующем тике.
     */
    private boolean strictCrit(LocalPlayer player) {
        boolean falling = player.fallDistance > 0.0 && !player.onGround() && !player.onClimbable()
                && !player.isInWater() && !player.isPassenger();
        if (!falling) return false;
        if (player.isSprinting()) {
            this.sprintReset.start(1, false);
            return false;
        }
        return true;
    }

    private boolean critReady(LocalPlayer player) {
        boolean airRising = !player.onGround() && player.getDeltaMovement().y > 0.0
                && !player.isInWater() && !player.onClimbable();
        if (!airRising) return true;
        return ++this.critWait > MAX_CRIT_WAIT_TICKS;
    }

    // ------------------------------------------------------------------
    // Выбор цели
    // ------------------------------------------------------------------

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        double reach = this.range.get();
        Vec3 eye = player.getEyePosition();
        Vec3 look = viewVector(player.getYRot(), player.getXRot());
        boolean rage = this.rage.isOn();
        double maxAngle = rage ? 180.0 : this.fov.get() * 0.5;
        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity e : client.level.getEntities(player, player.getBoundingBox().inflate(reach + 1.0),
                x -> this.isValidTarget(player, x))) {
            AABB box = e.getBoundingBox();
            double dist = eye.distanceTo(nearestPoint(eye, box));
            if (dist > reach) continue;
            Vec3 dir = box.getCenter().subtract(eye);
            double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir.normalize()), -1.0, 1.0)));
            if (angle > maxAngle || !player.hasLineOfSight(e)) continue;
            double score = rage ? dist : angle + dist * 6.0;
            if (score < bestScore) { bestScore = score; best = e; }
        }

        // Текущая цель держится, пока не отошла дальше RELEASE_RANGE (или не скрылась / не умерла).
        Entity locked = this.lockedTarget;
        if (locked != null && this.isValidTarget(player, locked) && player.hasLineOfSight(locked)) {
            double dist = eye.distanceTo(nearestPoint(eye, locked.getBoundingBox()));
            if (dist <= RELEASE_RANGE) {
                boolean inReach = dist <= reach;
                // другую цель берём, если текущая вне досягаемости, либо новая заметно лучше
                if (best == null || best == locked) {
                    best = locked;
                } else if (inReach && !rage) {
                    Vec3 dir = locked.getBoundingBox().getCenter().subtract(eye);
                    double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(dir.normalize()), -1.0, 1.0)));
                    double lockedScore = angle + dist * 6.0;
                    if (bestScore > lockedScore * 0.7 - 5.0) best = locked;
                }
            }
        }
        this.lockedTarget = best;
        return best;
    }

    private boolean isValidTarget(LocalPlayer player, Entity entity) {
        if (entity == player || !(entity instanceof LivingEntity living) || !living.isAlive()
                || entity instanceof ArmorStand
                || (this.skipAllies.isOn() && player.isAlliedTo(entity))) {
            return false;
        }
        if (entity instanceof Player other) {
            return this.players.isOn() && !other.isSpectator()
                    && !FriendManager.isFriend(other.getGameProfile().name());
        }
        return entity.getType().getCategory() == MobCategory.MONSTER ? this.monsters.isOn() : this.animals.isOn();
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
                || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
    }

    private static boolean canWork(Minecraft client, LocalPlayer player) {
        return player != null && client.level != null && client.screen == null
                && player.isAlive() && !player.isUsingItem();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.target = null;
        this.lockedTarget = null;
        this.active = false;
        this.aimValid = false;
        this.critWait = 0;
        this.sprintReset.cancel();
    }
}
