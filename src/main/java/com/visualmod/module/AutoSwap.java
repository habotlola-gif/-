package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.module.ModuleManager;
import com.visualmod.setting.KeybindSetting;
import com.visualmod.setting.ModeSetting;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoSwap
extends Module {
    private static final int TYPE_TOTEM = 0;
    private static final int TYPE_SPHERE = 1;
    private static final String[] TYPE_NAMES = new String[]{"\u0422\u0430\u043b\u0438\u0441\u043c\u0430\u043d/\u0422\u043e\u0442\u0435\u043c", "\u0428\u0430\u0440/\u0421\u0444\u0435\u0440\u0430"};
    private static final int OFFHAND_SLOT = 45;
    private static final int FIRST_INV_SLOT = 9;
    private static final int LAST_HOTBAR_SLOT = 44;
    public final KeybindSetting swapKey = this.setting(new KeybindSetting("key", "\u041a\u043b\u0430\u0432\u0438\u0448\u0430", -1));
    public final ModeSetting item1 = this.setting(new ModeSetting("item1", "\u041f\u0440\u0435\u0434\u043c\u0435\u0442 1", TYPE_NAMES));
    public final ModeSetting item2 = this.setting(new ModeSetting("item2", "\u041f\u0440\u0435\u0434\u043c\u0435\u0442 2", TYPE_NAMES));
    private boolean pending;
    private int currentCycle;
    private int targetCycle;
    private int stage;
    private int source = -1;
    private int pauseTicks;
    private int tickNumber;
    private int readyAtTick;
    private boolean pauseArmed;
    private boolean pauseThroughTick;
    private ItemStack expectedOffhand = ItemStack.EMPTY;
    private ItemStack expectedSource = ItemStack.EMPTY;

    public AutoSwap() {
        super("AutoSwap", "\u0426\u0438\u043a\u043b \u043f\u0440\u0435\u0434\u043c\u0435\u0442\u043e\u0432 \u0432 \u043e\u0444\u0445\u0435\u043d\u0434 \u043f\u043e \u043a\u043b\u0430\u0432\u0438\u0448\u0435", Category.COMBAT);
        this.item1.setIndex(0);
        this.item2.setIndex(1);
    }

    public boolean isSwapping() {
        return this.stage != 0;
    }

    public boolean pauseMovementInput() {
        return this.pauseArmed || this.pauseThroughTick;
    }

    public void beforePlayerTick(LocalPlayer player) {
        if (this.pauseMovementInput()) {
            player.setSprinting(false);
        }
    }

    public void afterPlayerTick() {
        if (this.pauseArmed && this.stage == 1) {
            ++this.pauseTicks;
        }
        this.pauseThroughTick = false;
    }

    public void onHandleKeybinds(Minecraft client) {
        ++this.tickNumber;
        if (!this.isEnabled() && this.stage == 0) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || !player.isAlive() || player.isSpectator()) {
            this.reset();
            return;
        }
        if (this.stage == 0) {
            if (this.pending && client.screen == null && !ModuleManager.CLICK_ACTIONS.isBusy()) {
                this.pending = false;
                this.trigger();
            }
            return;
        }
        if (this.stage == 2) {
            if (this.tickNumber >= this.readyAtTick) {
                if (ItemStack.matches((ItemStack)player.getOffhandItem(), (ItemStack)this.expectedOffhand) && ItemStack.matches((ItemStack)player.inventoryMenu.getSlot(this.source).getItem(), (ItemStack)this.expectedSource) && player.inventoryMenu.getCarried().isEmpty()) {
                    this.currentCycle = this.targetCycle;
                } else {
                    AutoSwap.message("\u00a7cAutoSwap: \u0441\u0435\u0440\u0432\u0435\u0440 \u043d\u0435 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u043b \u043e\u0431\u043c\u0435\u043d");
                }
                this.reset();
            }
            return;
        }
        if (client.screen != null || player.isPassenger()) {
            this.reset();
            return;
        }
        if (ModuleManager.CLICK_ACTIONS.isBusy() || ModuleManager.actedThisTick || client.options.keyAttack.isDown() || client.options.keyUse.isDown() || client.options.keySwapOffhand.isDown() || client.options.keyDrop.isDown() || client.options.keyPickItem.isDown() || player.isUsingItem()) {
            this.pauseArmed = false;
            this.pauseTicks = 0;
            return;
        }
        this.pauseArmed = true;
        if (this.pauseTicks < 2) {
            return;
        }
        int type = this.activeTypes()[this.targetCycle];
        if (!this.matches(player.inventoryMenu.getSlot(this.source).getItem(), type)) {
            this.source = this.findSlot(player, type);
        }
        if (this.source < 0 || !player.inventoryMenu.getCarried().isEmpty()) {
            AutoSwap.message("\u00a7cAutoSwap: \u043f\u0440\u0435\u0434\u043c\u0435\u0442 \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d \u0438\u043b\u0438 \u0437\u0430\u043d\u044f\u0442 \u043a\u0443\u0440\u0441\u043e\u0440");
            this.reset();
            return;
        }
        this.expectedOffhand = player.inventoryMenu.getSlot(this.source).getItem().copy();
        this.expectedSource = player.getOffhandItem().copy();
        int container = player.inventoryMenu.containerId;
        client.gameMode.handleInventoryMouseClick(container, this.source, 0, ClickType.PICKUP, (Player)player);
        client.gameMode.handleInventoryMouseClick(container, 45, 0, ClickType.PICKUP, (Player)player);
        client.gameMode.handleInventoryMouseClick(container, this.source, 0, ClickType.PICKUP, (Player)player);
        this.readyAtTick = this.tickNumber + Math.max(2, Math.min(30, (AutoSwap.latencyMs(client, player) + 49) / 50 + 1));
        this.stage = 2;
        this.pauseArmed = false;
        this.pauseThroughTick = true;
        ModuleManager.actedThisTick = true;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.screen != null && this.stage == 1) {
            this.reset();
        }
    }

    public void trigger() {
        int cycle;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || this.stage != 0 || ModuleManager.CLICK_ACTIONS.isBusy()) {
            return;
        }
        int[] types = this.activeTypes();
        int slot = this.findSlot(player, types[cycle = Math.floorMod(this.currentCycle + 1, types.length)]);
        if (slot < 0) {
            AutoSwap.message("\u00a7c\u041f\u0440\u0435\u0434\u043c\u0435\u0442 \u0434\u043b\u044f \u0441\u043b\u0435\u0434\u0443\u044e\u0449\u0435\u0433\u043e \u0441\u0432\u0430\u043f\u0430 \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d");
            return;
        }
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            AutoSwap.message("\u00a7cAutoSwap: \u0441\u043d\u0430\u0447\u0430\u043b\u0430 \u043e\u0441\u0432\u043e\u0431\u043e\u0434\u0438\u0442\u0435 \u043a\u0443\u0440\u0441\u043e\u0440");
            return;
        }
        this.source = slot;
        this.targetCycle = cycle;
        this.pauseTicks = 0;
        this.pauseArmed = true;
        this.stage = 1;
    }

    private int[] activeTypes() {
        return new int[]{this.item1.getIndex(), this.item2.getIndex()};
    }

    private int findSlot(LocalPlayer player, int type) {
        for (int slot = 9; slot <= 44; ++slot) {
            if (!this.matches(player.inventoryMenu.getSlot(slot).getItem(), type)) continue;
            return slot;
        }
        return -1;
    }

    private boolean matches(ItemStack stack, int type) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (type) {
            case 0 -> stack.is(Items.TOTEM_OF_UNDYING);
            case 1 -> {
                if (stack.is(Items.PLAYER_HEAD) && AutoSwap.isSphereName(stack.getHoverName().getString())) {
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private static boolean isSphereName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("\u0441\u0444\u0435\u0440\u0430") || lower.contains("\u0448\u0430\u0440");
    }

    private static int latencyMs(Minecraft client, LocalPlayer player) {
        ClientPacketListener connection = client.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    private void reset() {
        this.stage = 0;
        this.source = -1;
        this.pauseTicks = 0;
        this.pauseArmed = false;
        this.pauseThroughTick = false;
        this.expectedOffhand = ItemStack.EMPTY;
        this.expectedSource = ItemStack.EMPTY;
        this.pending = false;
    }

    private static void message(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage((Component)Component.literal((String)text));
        }
    }

    @Override
    public void onBind(int bind) {
        if (this.isEnabled() && this.stage == 0 && this.swapKey.matches(bind)) {
            this.pending = true;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.reset();
        this.currentCycle = 0;
    }
}

