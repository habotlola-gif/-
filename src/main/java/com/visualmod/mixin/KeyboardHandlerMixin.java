package com.visualmod.mixin;

import com.visualmod.module.ModuleManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={KeyboardHandler.class})
public abstract class KeyboardHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method={"keyPress"}, at={@At(value="HEAD")})
    private void visualmod$moduleBinds(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1 && event.key() != -1 && window == this.minecraft.getWindow().handle() && this.minecraft.screen == null) {
            ModuleManager.onBindPressed(event.key());
        }
    }
}

