package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.Removals;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={Gui.class})
public abstract class GuiMixin {
    @WrapWithCondition(method={"renderCameraOverlays"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/gui/Gui;renderTextureOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/Identifier;F)V")})
    private boolean visualmod$removeOverlays(Gui gui, GuiGraphics graphics, Identifier texture, float alpha) {
        Removals removals = ModuleManager.REMOVALS;
        return !removals.removes(removals.overlays);
    }

    @Inject(method={"renderPortalOverlay"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$removePortalOverlay(GuiGraphics graphics, float intensity, CallbackInfo ci) {
        Removals removals = ModuleManager.REMOVALS;
        if (removals.removes(removals.nausea)) {
            ci.cancel();
        }
    }

    @Inject(method={"renderConfusionOverlay"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$removeConfusionOverlay(GuiGraphics graphics, float intensity, CallbackInfo ci) {
        Removals removals = ModuleManager.REMOVALS;
        if (removals.removes(removals.nausea)) {
            ci.cancel();
        }
    }
}

