package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

public class AutoMace
extends Module {
    public final NumberSetting minFall = this.setting(new NumberSetting("min_fall", "\u041c\u0438\u043d. \u043f\u0430\u0434\u0435\u043d\u0438\u0435", 2.0, 1.5, 10.0, 0.5, " \u0431\u043b."));
    public final BooleanSetting swapBack = this.setting(new BooleanSetting("swap_back", "\u0412\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u0442\u044c \u0441\u043b\u043e\u0442", true));
    public final BooleanSetting onlyLiving = this.setting(new BooleanSetting("only_living", "\u0422\u043e\u043b\u044c\u043a\u043e \u043f\u043e \u0436\u0438\u0432\u044b\u043c", true));
    private static final int RESTORE_DELAY_TICKS = 2;
    private int previousSlot = -1;
    private int restoreIn;

    public AutoMace() {
        super("AutoMace", "\u0411\u0443\u043b\u0430\u0432\u0430 \u0432 \u0440\u0443\u043a\u0443 \u043f\u0440\u0438 \u0443\u0434\u0430\u0440\u0435 \u0432 \u043f\u0430\u0434\u0435\u043d\u0438\u0438", Category.COMBAT);
    }

    public void onAttack(Player player, Entity target) {
        if (!this.isEnabled() || this.onlyLiving.isOn() && !(target instanceof LivingEntity)) {
            return;
        }
        if (player.onGround() || player.isFallFlying() || player.fallDistance < this.minFall.get()) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedItem().is(Items.MACE)) {
            return;
        }
        for (int slot = 0; slot < 9; ++slot) {
            if (!inventory.getItem(slot).is(Items.MACE)) continue;
            if (this.previousSlot < 0) {
                this.previousSlot = inventory.getSelectedSlot();
            }
            inventory.setSelectedSlot(slot);
            this.restoreIn = 2;
            return;
        }
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

