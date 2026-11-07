package com.visualmod.module;

import com.visualmod.FriendManager;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoMace
extends Module {
    public final NumberSetting minFall = this.setting(new NumberSetting("min_fall", "\u041c\u0438\u043d. \u043f\u0430\u0434\u0435\u043d\u0438\u0435", 2.0, 1.5, 10.0, 0.5, " \u0431\u043b."));
    public final BooleanSetting swapBack = this.setting(new BooleanSetting("swap_back", "\u0412\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u0442\u044c \u0441\u043b\u043e\u0442", true));
    public final BooleanSetting onlyLiving = this.setting(new BooleanSetting("only_living", "\u0422\u043e\u043b\u044c\u043a\u043e \u043f\u043e \u0436\u0438\u0432\u044b\u043c", true));
    public final BooleanSetting swordFallback = this.setting(new BooleanSetting("sword_fallback", "Меч без падения", true));
    public final BooleanSetting wind = this.setting(new BooleanSetting("wind", "Ветрозаряд-запуск", false));
    public final NumberSetting windRange = this.setting(new NumberSetting("wind_range", "Дальность запуска", 6.0, 3.0, 10.0, 0.5, " бл."));
    public final BooleanSetting preSwap = this.setting(new BooleanSetting("pre_swap", "Свап заранее", true));
    public final NumberSetting restoreDelay = this.setting(new NumberSetting("restore_delay", "Возврат слота через", 2.5, 0.5, 5.0, 0.5, " с"));
    private int pendingMaceSlot = -1;
    private int pendingMaceIn;
    private int windCooldown;
    private boolean launched;
    private int previousSlot = -1;
    private int restoreIn;

    public AutoMace() {
        super("AutoMace", "Булава при падении, меч без падения, ветрозаряд-запуск", Category.COMBAT);
    }

    public void onAttack(Player player, Entity target) {
        if (!this.isEnabled() || this.onlyLiving.isOn() && !(target instanceof LivingEntity)) {
            return;
        }
        Inventory inventory = player.getInventory();
        boolean smash = !player.onGround() && !player.isFallFlying() && player.fallDistance >= this.minFall.get();
        if (smash) {
            // падаем: булава даёт удар-«смэш», берём её
            if (inventory.getSelectedItem().is(Items.MACE)) {
                return;
            }
            for (int slot = 0; slot < 9; ++slot) {
                if (!inventory.getItem(slot).is(Items.MACE)) continue;
                this.swapTo(inventory, slot);
                return;
            }
        } else if (this.swordFallback.isOn() && inventory.getSelectedItem().is(Items.MACE)) {
            // без падения булава бьёт слабо: на этот удар берём меч (или топор)
            int best = -1;
            for (int slot = 0; slot < 9; ++slot) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(ItemTags.SWORDS)) { best = slot; break; }
                if (best < 0 && stack.is(ItemTags.AXES)) best = slot;
            }
            if (best >= 0) {
                this.swapTo(inventory, best);
            }
        }
    }

    private void swapTo(Inventory inventory, int slot) {
        if (this.previousSlot < 0) {
            this.previousSlot = inventory.getSelectedSlot();
        }
        inventory.setSelectedSlot(slot);
        this.restoreIn = Math.max(1, Math.round(this.restoreDelay.getFloat() * 20.0f));
    }

    /**
     * Ветрозаряд-запуск (HEAD LocalPlayer.tick): ты прыгнул, рядом враг, в хотбаре есть ветрозаряд и булава.
     * Смотрим вниз, бросаем заряд под себя (подбрасывает вверх), берём булаву и падаем на цель со смэшем.
     * Сам удар делает KillAura. Прыгать надо самому.
     */
    public void beforePlayerTick(LocalPlayer player) {
        if (this.windCooldown > 0) --this.windCooldown;
        // булава в руку не в тот же тик, что бросок: слишком быстрая смена слота выглядит как BadPackets
        if (this.pendingMaceSlot >= 0 && --this.pendingMaceIn <= 0) {
            player.getInventory().setSelectedSlot(this.pendingMaceSlot);
            this.pendingMaceSlot = -1;
        }
        if (player.onGround() || player.isInWater()) this.launched = false;
        Minecraft mc = Minecraft.getInstance();
        this.preSwap(player);
        if (!this.isEnabled() || !this.wind.isOn() || this.launched || this.windCooldown > 0
                || player.onGround() || player.isFallFlying() || player.isPassenger()
                || mc.gameMode == null || mc.level == null || mc.screen != null
                || player.getDeltaMovement().y > 0.2) {
            return;
        }
        Inventory inv = player.getInventory();
        int windSlot = -1, maceSlot = -1;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inv.getItem(slot);
            if (windSlot < 0 && stack.is(Items.WIND_CHARGE)) windSlot = slot;
            if (maceSlot < 0 && stack.is(Items.MACE)) maceSlot = slot;
        }
        if (windSlot < 0 || maceSlot < 0 || !hasTarget(mc, player)) return;

        float yaw = player.getYRot(), pitch = player.getXRot();
        float yawO = player.yRotO, pitchO = player.xRotO;
        inv.setSelectedSlot(windSlot);
        player.setXRot(90.0f);                       // бросок строго вниз, под себя
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yawO;
        player.xRotO = pitchO;
        this.pendingMaceSlot = maceSlot;             // булава в руку через несколько тиков
        this.pendingMaceIn = 4;
        this.previousSlot = -1;
        this.launched = true;
        this.windCooldown = 30;
    }

    /** Падаем и цель вот-вот в зоне удара: берём булаву за 1-2 тика до удара, а не в момент самого удара. */
    private void preSwap(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (!this.isEnabled() || !this.preSwap.isOn() || mc.level == null || mc.screen != null
                || player.onGround() || player.isFallFlying() || player.fallDistance < this.minFall.get()) {
            return;
        }
        Inventory inv = player.getInventory();
        if (inv.getSelectedItem().is(Items.MACE)) return;
        Entity t = ModuleManager.KILL_AURA.currentTarget();
        boolean near;
        if (t != null) {
            near = player.distanceTo(t) <= ModuleManager.KILL_AURA.range.get() + 1.5;
        } else {
            near = false;
            for (Player other : mc.level.players()) {
                if (other == player || other.isSpectator() || !other.isAlive()
                        || FriendManager.isFriend(other.getGameProfile().name())) continue;
                if (player.distanceTo(other) <= 4.5) { near = true; break; }
            }
        }
        if (!near) return;
        for (int slot = 0; slot < 9; ++slot) {
            if (inv.getItem(slot).is(Items.MACE)) {
                this.swapTo(inv, slot);
                return;
            }
        }
    }

    private boolean hasTarget(Minecraft mc, LocalPlayer self) {
        double r = this.windRange.get();
        for (Player other : mc.level.players()) {
            if (other == self || other.isSpectator() || !other.isAlive()) continue;
            if (FriendManager.isFriend(other.getGameProfile().name())) continue;
            double dx = other.getX() - self.getX(), dz = other.getZ() - self.getZ();
            if (Math.hypot(dx, dz) <= r && Math.abs(other.getY() - self.getY()) <= 4.0) return true;
        }
        return false;
    }

    @Override
    public void onTick(Minecraft client) {
        if (this.previousSlot < 0 || client.player == null) {
            return;
        }
        if (--this.restoreIn <= 0) {
            if (this.swapBack.isOn()) {
                client.player.getInventory().setSelectedSlot(this.previousSlot);
            }
            this.previousSlot = -1;
        }
    }
}

