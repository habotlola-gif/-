package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.module.ModuleManager;
import com.visualmod.setting.KeybindSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ClickActions
extends Module {
    public final KeybindSetting pearlKey = this.setting(new KeybindSetting("pearl_key", "\u042d\u043d\u0434\u0435\u0440-\u043f\u0435\u0440\u043b", -1));
    public final KeybindSetting windChargeKey = this.setting(new KeybindSetting("wind_charge_key", "\u0417\u0430\u0440\u044f\u0434 \u0432\u0435\u0442\u0440\u0430", -1));
    private Item pending;
    private Item item;
    private int stage;
    private int previousSlot = -1;
    private int itemSlot = -1;
    private int backpackSlot = -1;
    private int pauseTicks;
    private int tickNumber;
    private int readyAtTick;
    private boolean pauseThroughTick;
    private boolean pauseArmed;
    private boolean borrowed;
    private ItemStack borrowedStack = ItemStack.EMPTY;
    private ItemStack displacedStack = ItemStack.EMPTY;
    private InteractionHand hand = InteractionHand.MAIN_HAND;

    public ClickActions() {
        super("Click Actions", "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0435 \u043f\u0435\u0440\u043b\u0430 \u0438 \u0437\u0430\u0440\u044f\u0434\u0430 \u0432\u0435\u0442\u0440\u0430 \u043f\u043e \u043a\u043b\u0430\u0432\u0438\u0448\u0430\u043c", Category.COMBAT);
    }

    public boolean isBusy() {
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
        if (this.pauseArmed && (this.stage == 4 || this.stage == 8)) {
            ++this.pauseTicks;
        }
        this.pauseThroughTick = false;
    }

    @Override
    public void onBind(int bind) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.pearlKey.matches(bind)) {
            this.pending = Items.ENDER_PEARL;
        } else if (this.windChargeKey.matches(bind)) {
            this.pending = Items.WIND_CHARGE;
        }
    }

    public void triggerUse(Item requested) {
        if (this.isEnabled() && (requested == Items.ENDER_PEARL || requested == Items.WIND_CHARGE)) {
            this.pending = requested;
        }
    }

    public boolean requestExternalUse(Item requested) {
        if (requested == null || this.stage != 0 || this.pending != null) {
            return false;
        }
        this.pending = requested;
        return true;
    }

    public void onHandleKeybinds(Minecraft client) {
        ++this.tickNumber;
        if (!this.isEnabled() && this.stage == 0 && this.pending == null) {
            this.pending = null;
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || !player.isAlive() || player.isSpectator()) {
            this.reset();
            return;
        }
        if (this.stage == 0) {
            if (this.pending == null || client.screen != null) {
                return;
            }
            if (ModuleManager.AUTO_SWAP.isSwapping()) {
                return;
            }
            this.item = this.pending;
            this.pending = null;
            this.stage = 1;
        }
        if (client.screen != null) {
            this.requestCleanup();
            return;
        }
        if (ModuleManager.actedThisTick || ModuleManager.AUTO_SWAP.isSwapping() || client.options.keyAttack.isDown() || client.options.keyUse.isDown() || player.isUsingItem()) {
            if (this.stage == 4 || this.stage == 8) {
                this.pauseArmed = false;
                this.pauseTicks = 0;
            }
            return;
        }
        if (this.stage == 4 || this.stage == 8) {
            this.pauseArmed = true;
        }
        switch (this.stage) {
            case 1: {
                if (player.getMainHandItem().is(this.item)) {
                    this.hand = InteractionHand.MAIN_HAND;
                    this.stage = 2;
                    break;
                }
                if (player.getOffhandItem().is(this.item)) {
                    this.hand = InteractionHand.OFF_HAND;
                    this.stage = 2;
                    break;
                }
                this.itemSlot = ClickActions.findHotbarSlot(player, this.item);
                if (this.itemSlot < 0) {
                    this.backpackSlot = ClickActions.findBackpackSlot(player, this.item);
                    if (this.backpackSlot < 0) {
                        ClickActions.message("\u00a7c\u041f\u0440\u0435\u0434\u043c\u0435\u0442 \u0434\u043b\u044f Click Actions \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d");
                        this.reset();
                        return;
                    }
                    if (player.isPassenger() || !player.inventoryMenu.getCarried().isEmpty()) {
                        ClickActions.message("\u00a7c\u041d\u0435\u043b\u044c\u0437\u044f \u0432\u0437\u044f\u0442\u044c \u043f\u0440\u0435\u0434\u043c\u0435\u0442 \u0438\u0437 \u0440\u044e\u043a\u0437\u0430\u043a\u0430 \u0441\u0435\u0439\u0447\u0430\u0441");
                        this.reset();
                        return;
                    }
                    this.previousSlot = player.getInventory().getSelectedSlot();
                    this.pauseTicks = 0;
                    this.stage = 4;
                    this.pauseArmed = true;
                    return;
                }
                this.previousSlot = player.getInventory().getSelectedSlot();
                player.getInventory().setSelectedSlot(this.itemSlot);
                this.stage = 2;
                ModuleManager.actedThisTick = true;
                break;
            }
            case 2: {
                if (player.getItemInHand(this.hand).is(this.item)) {
                    client.gameMode.useItem((Player)player, this.hand);
                    ModuleManager.actedThisTick = true;
                }
                int n = this.stage = this.previousSlot >= 0 ? 3 : 0;
                if (this.stage != 0) break;
                this.reset();
                break;
            }
            case 3: {
                if (this.previousSlot >= 0) {
                    player.getInventory().setSelectedSlot(this.previousSlot);
                }
                this.reset();
                ModuleManager.actedThisTick = true;
                break;
            }
            case 4: {
                if (this.pauseTicks < 2) {
                    return;
                }
                if (!player.inventoryMenu.getSlot(this.backpackSlot).getItem().is(this.item) || player.getInventory().getSelectedSlot() != this.previousSlot) {
                    this.reset();
                    return;
                }
                this.borrowedStack = player.inventoryMenu.getSlot(this.backpackSlot).getItem().copy();
                this.displacedStack = player.getInventory().getItem(this.previousSlot).copy();
                this.swapBackpack(client, player);
                this.borrowed = true;
                this.pauseThroughTick = true;
                this.pauseArmed = false;
                this.readyAtTick = this.tickNumber + Math.max(2, Math.min(30, (ClickActions.latencyMs(client, player) + 49) / 50 + 1));
                this.stage = 5;
                ModuleManager.actedThisTick = true;
                break;
            }
            case 5: {
                if (this.tickNumber < this.readyAtTick) {
                    return;
                }
                if (player.getInventory().getSelectedSlot() == this.previousSlot && ItemStack.matches((ItemStack)player.inventoryMenu.getSlot(this.backpackSlot).getItem(), (ItemStack)this.displacedStack) && ItemStack.matches((ItemStack)player.getMainHandItem(), (ItemStack)this.borrowedStack)) {
                    client.gameMode.useItem((Player)player, InteractionHand.MAIN_HAND);
                    ModuleManager.actedThisTick = true;
                }
                this.stage = 7;
                break;
            }
            case 7: {
                this.pauseTicks = 0;
                this.stage = 8;
                this.pauseArmed = true;
                break;
            }
            case 8: {
                if (this.pauseTicks < 2) {
                    return;
                }
                ItemStack working = player.getInventory().getItem(this.previousSlot);
                if (this.borrowed && ItemStack.matches((ItemStack)player.inventoryMenu.getSlot(this.backpackSlot).getItem(), (ItemStack)this.displacedStack) && (working.isEmpty() || ItemStack.isSameItemSameComponents((ItemStack)working, (ItemStack)this.borrowedStack))) {
                    this.swapBackpack(client, player);
                    this.pauseThroughTick = true;
                    ModuleManager.actedThisTick = true;
                }
                this.reset();
                break;
            }
            default: {
                this.reset();
            }
        }
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.screen != null && this.stage != 0) {
            this.requestCleanup();
        }
    }

    private void requestCleanup() {
        this.pauseArmed = false;
        this.pauseTicks = 0;
        if (this.borrowed) {
            this.stage = 7;
        } else if (this.previousSlot >= 0 && this.stage <= 3) {
            this.stage = 3;
        } else {
            this.reset();
        }
    }

    private static int findHotbarSlot(LocalPlayer player, Item requested) {
        for (int slot = 0; slot < 9; ++slot) {
            if (!player.getInventory().getItem(slot).is(requested)) continue;
            return slot;
        }
        return -1;
    }

    private static int findBackpackSlot(LocalPlayer player, Item requested) {
        for (int slot = 9; slot < 36; ++slot) {
            if (!player.inventoryMenu.getSlot(slot).getItem().is(requested)) continue;
            return slot;
        }
        return -1;
    }

    private static int latencyMs(Minecraft client, LocalPlayer player) {
        PlayerInfo info = client.getConnection().getPlayerInfo(player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    private void swapBackpack(Minecraft client, LocalPlayer player) {
        int container = player.inventoryMenu.containerId;
        client.gameMode.handleInventoryMouseClick(container, this.backpackSlot, this.previousSlot, ClickType.SWAP, (Player)player);
        player.connection.send((Packet)new ServerboundContainerClosePacket(container));
    }

    private void reset() {
        this.stage = 0;
        this.item = null;
        this.previousSlot = -1;
        this.itemSlot = -1;
        this.backpackSlot = -1;
        this.borrowed = false;
        this.borrowedStack = ItemStack.EMPTY;
        this.displacedStack = ItemStack.EMPTY;
        this.pauseArmed = false;
        this.pending = null;
    }

    private static void message(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage((Component)Component.literal((String)text));
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.pending = null;
        if (!enabled && this.stage != 0) {
            this.requestCleanup();
        } else if (enabled && this.stage == 0) {
            this.reset();
        }
    }
}

