package com.visualmod.module;

import com.visualmod.mixin.LivingEntityAccessor;
import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.Minecraft;

public class NoJumpDelay
extends Module {
    public final NumberSetting delay = this.setting(new NumberSetting("delay", "\u0417\u0430\u0434\u0435\u0440\u0436\u043a\u0430", 0.0, 0.0, 10.0, 1.0, " \u0442\u0438\u043a."));

    public NoJumpDelay() {
        super("NoJumpDelay", "\u0411\u0435\u0437 \u0437\u0430\u0434\u0435\u0440\u0436\u043a\u0438 \u043c\u0435\u0436\u0434\u0443 \u043f\u0440\u044b\u0436\u043a\u0430\u043c\u0438", Category.MOVEMENT);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        LivingEntityAccessor player = (LivingEntityAccessor)client.player;
        if (player.visualmod$getNoJumpDelay() > this.delay.getInt()) {
            player.visualmod$setNoJumpDelay(this.delay.getInt());
        }
    }
}

