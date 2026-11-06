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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
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
    public final KeybindSetting swapKey = this.setting(new KeybindSetting("key", "Клавиша 1", -1));
    public final ModeSetting item1 = this.setting(new ModeSetting("item1", "Предмет 1", TYPE_NAMES));
    public final KeybindSetting swapKey2 = this.setting(new KeybindSetting("key2", "Клавиша 2", -1));
    public final ModeSetting item2 = this.setting(new ModeSetting("item2", "Предмет 2", TYPE_NAMES));
    private int pending;        // 0 нет, 1 клавиша 1, 2 клавиша 2
    private int targetType;
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
        this.item1.setIndex(1);   // Шар/Сфера
        this.item2.setIndex(0);   // Талисман/Тотем
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
            if (this.pending != 0 && client.screen == null && !ModuleManager.CLICK_ACTIONS.isBusy()) {
                int which = this.pending;
                this.pending = 0;
                this.trigger(which);
            }
            return;
        }
        if (this.stage == 2) {
            if (this.tickNumber >= this.readyAtTick) {
                if (ItemStack.matches((ItemStack)player.getOffhandItem(), (ItemStack)this.expectedOffhand) && ItemStack.matches((ItemStack)player.inventoryMenu.getSlot(this.source).getItem(), (ItemStack)this.expectedSource) && player.inventoryMenu.getCarried().isEmpty()) {
                } else {
                    AutoSwap.message("§cAutoSwap: сервер не подтвердил обмен (слот " + this.source + "), попробуй ещё раз");
                }
                this.reset();
            }
            return;
        }
        if (client.screen != null || player.isPassenger()) {
            this.reset();
            return;
        }
        // Предмет в хотбаре меняется как клавишей F (без инвентаря), предмет в сумке одним кликом «обмен с оффхендом».
        boolean viaHotbar = this.source >= 36 && this.source <= 44;
        if (player.isUsingItem() || ModuleManager.CLICK_ACTIONS.isBusy()
                || (!viaHotbar && (ModuleManager.actedThisTick || client.options.keyAttack.isDown() || client.options.keyUse.isDown()
                || client.options.keySwapOffhand.isDown() || client.options.keyDrop.isDown() || client.options.keyPickItem.isDown()))) {
            this.pauseArmed = false;
            this.pauseTicks = 0;
            return;
        }
        // Для клика по сумке 2 тика стоим на месте (инвентарные проверки), для хотбара пауза не нужна.
        this.pauseArmed = !viaHotbar;
        if (!viaHotbar && this.pauseTicks < 2) {
            return;
        }
        int type = this.targetType;
        if (!this.matches(player.inventoryMenu.getSlot(this.source).getItem(), type)) {
            this.source = this.findSlot(player, type);
            viaHotbar = this.source >= 36 && this.source <= 44;
        }
        if (this.source < 0) {
            AutoSwap.message("§cAutoSwap: предмет пропал из инвентаря (" + TYPE_NAMES[type] + ")");
            this.reset();
            return;
        }
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            AutoSwap.message("§cAutoSwap: курсор занят предметом, обмен отменён");
            this.reset();
            return;
        }
        this.expectedOffhand = player.inventoryMenu.getSlot(this.source).getItem().copy();
        this.expectedSource = player.getOffhandItem().copy();
        ClientPacketListener net = client.getConnection();
        if (net == null) {
            this.reset();
            return;
        }
        if (viaHotbar) {
            int original = player.getInventory().getSelectedSlot();
            net.send(new ServerboundSetCarriedItemPacket(this.source - 36));
            net.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            net.send(new ServerboundSetCarriedItemPacket(original));
        } else {
            client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, this.source, 40, ClickType.SWAP, (Player)player);
        }
        this.readyAtTick = this.tickNumber + Math.max(2, Math.min(30, (AutoSwap.latencyMs(client, player) + 49) / 50 + 2));
        this.stage = 2;
        this.pauseArmed = false;
        this.pauseThroughTick = !viaHotbar;
        ModuleManager.actedThisTick = true;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.screen != null && this.stage == 1) {
            this.reset();
        }
    }

    /**
     * Запуск свапа. which: 1 = клавиша 1 (категория «Предмет 1»), 2 = клавиша 2 («Предмет 2»).
     * Если в оффхенде уже предмет этой категории, берём ДРУГОЙ предмет той же категории (сфера A -> сфера B,
     * талисман A -> талисман B). Если там что-то другое (например талисман, а нужна сфера), берём первый подходящий.
     */
    public void trigger(int which) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || this.stage != 0 || ModuleManager.CLICK_ACTIONS.isBusy()) {
            return;
        }
        int type = which == 2 ? this.item2.getIndex() : this.item1.getIndex();
        int slot = this.findSlot(player, type);
        if (slot < 0) {
            AutoSwap.message("§cAutoSwap: в инвентаре нет подходящего предмета (" + TYPE_NAMES[type] + ")");
            return;
        }
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            AutoSwap.message("§cAutoSwap: сначала освободите курсор");
            return;
        }
        this.source = slot;
        this.targetType = type;
        this.pauseTicks = 0;
        this.pauseArmed = true;
        this.stage = 1;
    }

    public void trigger() {
        this.trigger(1);
    }

    private int[] activeTypes() {
        return new int[]{this.item1.getIndex(), this.item2.getIndex()};
    }

    private int findSlot(LocalPlayer player, int type) {
        ItemStack off = player.getOffhandItem();
        boolean offIsType = this.matches(off, type);
        for (int slot = 9; slot <= 44; ++slot) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (!this.matches(stack, type)) continue;
            // в оффхенде уже такой же предмет, ищем другой
            if (offIsType && ItemStack.isSameItemSameComponents(stack, off)) continue;
            return slot;
        }
        return -1;
    }

    /** Категории по ключевым словам в названии: подходит ЛЮБОЙ предмет с таким названием, не только голова/тотем. */
    private boolean matches(ItemStack stack, int type) {
        if (stack.isEmpty()) {
            return false;
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return switch (type) {
            case 0 -> stack.is(Items.TOTEM_OF_UNDYING) || name.contains("талисман") || name.contains("тотем") || name.contains("totem");
            case 1 -> name.contains("сфера") || name.contains("шар") || name.contains("sphere") || name.contains("orb");
            default -> false;
        };
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
        this.pending = 0;
    }

    private static void message(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage((Component)Component.literal((String)text));
        }
    }

    @Override
    public void onBind(int bind) {
        if (!this.isEnabled() || this.stage != 0) {
            return;
        }
        if (this.swapKey.matches(bind)) {
            this.pending = 1;
        } else if (this.swapKey2.matches(bind)) {
            this.pending = 2;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.reset();
    }
}

