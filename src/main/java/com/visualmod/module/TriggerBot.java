package com.visualmod.module;

import com.visualmod.FriendManager;
import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.SprintResetter;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import com.visualmod.setting.RangeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class TriggerBot
extends Module {
    public final BooleanSetting players = this.setting(new BooleanSetting("players", "\u0418\u0433\u0440\u043e\u043a\u0438", true));
    public final BooleanSetting monsters = this.setting(new BooleanSetting("monsters", "\u041c\u043e\u043d\u0441\u0442\u0440\u044b", true));
    public final BooleanSetting animals = this.setting(new BooleanSetting("animals", "\u0416\u0438\u0432\u043e\u0442\u043d\u044b\u0435", false));
    public final NumberSetting charge = this.setting(new NumberSetting("charge", "\u0417\u0430\u0440\u044f\u0434 \u0443\u0434\u0430\u0440\u0430", 100.0, 50.0, 100.0, 5.0, "%"));
    public final RangeSetting reaction = this.setting(new RangeSetting("reaction", "\u0420\u0430\u0437\u043d\u0438\u0446\u0430 \u0440\u0435\u0430\u043a\u0446\u0438\u0438", 60.0, 140.0, 0.0, 500.0, 10.0, " \u043c\u0441"));
    public final BooleanSetting weaponOnly = this.setting(new BooleanSetting("weapon_only", "\u0422\u043e\u043b\u044c\u043a\u043e \u0441 \u043e\u0440\u0443\u0436\u0438\u0435\u043c", true));
    public final BooleanSetting critsOnly = this.setting(new BooleanSetting("crits_only", "\u0422\u043e\u043b\u044c\u043a\u043e \u043a\u0440\u0438\u0442\u044b", false));
    public final BooleanSetting skipAllies = this.setting(new BooleanSetting("skip_allies", "\u041d\u0435 \u0431\u0438\u0442\u044c \u0441\u043e\u044e\u0437\u043d\u0438\u043a\u043e\u0432", true));
    public final BooleanSetting resetSprint = this.setting(new BooleanSetting("reset_sprint", "\u0421\u0431\u0440\u043e\u0441 \u0441\u043f\u0440\u0438\u043d\u0442\u0430", true));
    public final ModeSetting resetMode = this.setting(new ModeSetting("reset_mode", "\u041a\u0430\u043a \u0441\u0431\u0440\u0430\u0441\u044b\u0432\u0430\u0442\u044c", "W-Tap", "S-Tap"));
    public final NumberSetting resetTicks = this.setting(new NumberSetting("reset_ticks", "\u0414\u043b\u0438\u0442\u0435\u043b\u044c\u043d\u043e\u0441\u0442\u044c \u0441\u0431\u0440\u043e\u0441\u0430", 2.0, 1.0, 4.0, 1.0, " \u0442\u0438\u043a."));
    public final SprintResetter sprintReset = new SprintResetter();
    private long readySince = -1L;
    private long attackAt = -1L;
    private double lastReactionMs = -1.0;
    private static final long HALF_TICK_NANOS = 25000000L;

    public TriggerBot() {
        super("TriggerBot", "\u0423\u0434\u0430\u0440, \u043a\u043e\u0433\u0434\u0430 \u043f\u0440\u0438\u0446\u0435\u043b \u043d\u0430 \u0446\u0435\u043b\u0438", Category.COMBAT);
    }

    public void onHandleKeybinds(Minecraft client) {
        if (!this.isEnabled()) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || client.screen != null || !player.isAlive() || player.isUsingItem()) {
            this.reset();
            return;
        }
        Entity target = TriggerBot.targetUnderCrosshair(client);
        if (target == null || !this.isValidTarget(player, target) || !this.canAttackNow(player)) {
            this.reset();
            return;
        }
        long now = System.nanoTime();
        if (this.readySince < 0L) {
            this.readySince = now;
            this.attackAt = now + Math.round(this.reaction.random(player.getRandom()) * 1000000.0);
        }
        if (now + 25000000L < this.attackAt) {
            return;
        }
        this.lastReactionMs = (double)(now - this.readySince) / 1000000.0;
        boolean wasSprinting = player.isSprinting();
        client.gameMode.attack((Player)player, target);
        player.swing(InteractionHand.MAIN_HAND);
        ModuleManager.actedThisTick = true;
        if (this.resetSprint.isOn() && wasSprinting) {
            this.sprintReset.start(this.resetTicks.getInt(), this.resetMode.getIndex() == 1);
        }
        this.reset();
    }

    @Override
    public void onTick(Minecraft client) {
        this.sprintReset.tick();
    }

    private void reset() {
        this.readySince = -1L;
        this.attackAt = -1L;
    }

    public double lastReactionMs() {
        return this.lastReactionMs;
    }

    private static Entity targetUnderCrosshair(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult) {
            EntityHitResult entityHit = (EntityHitResult)hit;
            return entityHit.getEntity();
        }
        return null;
    }

    private boolean isValidTarget(LocalPlayer player, Entity target) {
        LivingEntity living;
        if (!(target instanceof LivingEntity) || !(living = (LivingEntity)target).isAlive() || target instanceof ArmorStand) {
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

    private boolean canAttackNow(LocalPlayer player) {
        if (player.getAttackStrengthScale(0.0f) < this.charge.getFloat() / 100.0f) {
            return false;
        }
        if (this.weaponOnly.isOn() && !TriggerBot.isWeapon(player.getMainHandItem())) {
            return false;
        }
        return !this.critsOnly.isOn() || player.fallDistance > 0.0 && !player.onGround() && !player.isInWater() && !player.onClimbable() && !player.isPassenger();
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.reset();
        this.sprintReset.cancel();
    }
}

