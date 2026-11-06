package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.module.ModuleManager;
import com.visualmod.setting.KeybindSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class FTHelper
extends Module {
    public static final Item[] ITEMS = new Item[]{Items.SNOWBALL, Items.PHANTOM_MEMBRANE, Items.NETHERITE_SCRAP, Items.DRIED_KELP, Items.SUGAR, Items.FIRE_CHARGE, Items.ENDER_EYE};
    private static final String[] KEY_NAMES = new String[]{"\u0421\u043d\u0435\u0436\u043e\u043a", "God's Aura", "Trap", "Plast", "Clear Dust", "Fire Tornado", "Disorientation"};
    private static final String[] KEY_IDS = new String[]{"key_snowball", "key_gods_aura", "key_trap", "key_plast", "key_clear_dust", "key_fire_tornado", "key_disorientation"};
    public final KeybindSetting[] keys = new KeybindSetting[ITEMS.length];
    private int pendingUse = -1;

    public FTHelper() {
        super("FTHelper", "\u041f\u0440\u0438\u043c\u0435\u043d\u0435\u043d\u0438\u0435 \u043f\u0440\u0435\u0434\u043c\u0435\u0442\u0430 \u043f\u043e \u043a\u043b\u0430\u0432\u0438\u0448\u0435 \u0441 \u0432\u043e\u0437\u0432\u0440\u0430\u0442\u043e\u043c", Category.COMBAT);
        for (int i = 0; i < this.keys.length; ++i) {
            this.keys[i] = this.setting(new KeybindSetting(KEY_IDS[i], KEY_NAMES[i], -1));
        }
    }

    public boolean isBusy() {
        return this.pendingUse >= 0;
    }

    @Override
    public void onBind(int bind) {
        if (!this.isEnabled()) {
            return;
        }
        for (int i = 0; i < this.keys.length; ++i) {
            if (!this.keys[i].matches(bind)) continue;
            this.pendingUse = i;
            return;
        }
    }

    public void triggerUse(int index) {
        if (this.isEnabled() && index >= 0 && index < ITEMS.length) {
            this.pendingUse = index;
        }
    }

    public void onHandleKeybinds(Minecraft client) {
        if (!this.isEnabled() || this.pendingUse < 0 || client.player == null || client.screen != null) {
            return;
        }
        if (ModuleManager.CLICK_ACTIONS.requestExternalUse(ITEMS[this.pendingUse])) {
            this.pendingUse = -1;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            this.pendingUse = -1;
        }
    }
}

