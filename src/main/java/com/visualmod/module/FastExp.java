package com.visualmod.module;

import com.visualmod.mixin.MinecraftAccessor;
import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;

public class FastExp
extends Module {
    public final NumberSetting delay = this.setting(new NumberSetting("delay", "\u0417\u0430\u0434\u0435\u0440\u0436\u043a\u0430", 0.0, 0.0, 4.0, 1.0, " \u0442\u0438\u043a."));
    public final BooleanSetting offhand = this.setting(new BooleanSetting("offhand", "\u0412\u043e \u0432\u0442\u043e\u0440\u043e\u0439 \u0440\u0443\u043a\u0435", true));

    public FastExp() {
        super("FastExp", "\u0411\u044b\u0441\u0442\u0440\u043e \u043a\u0438\u0434\u0430\u0442\u044c \u043f\u0443\u0437\u044b\u0440\u044c\u043a\u0438 \u043e\u043f\u044b\u0442\u0430", Category.PLAYER);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        boolean holding = client.player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE) || this.offhand.isOn() && client.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE);
        MinecraftAccessor accessor = (MinecraftAccessor)client;
        if (holding && accessor.visualmod$getRightClickDelay() > this.delay.getInt()) {
            accessor.visualmod$setRightClickDelay(this.delay.getInt());
        }
    }
}

