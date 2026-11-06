package com.visualmod.mixin;

import com.visualmod.module.Keybind;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={MouseHandler.class})
public abstract class MouseHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method={"onButton"}, at={@At(value="HEAD")})
    private void visualmod$moduleBinds(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (action == 1 && window == this.minecraft.getWindow().handle() && this.minecraft.screen == null) {
            ModuleManager.onBindPressed(Keybind.mouse(info.button()));
        }
    }
}

