package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.SprintResetter;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={KeyboardInput.class})
public abstract class KeyboardInputMixin {
    private static final String IS_DOWN = "Lnet/minecraft/client/KeyMapping;isDown()Z";

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=0)})
    private boolean visualmod$forward(boolean pressed) {
        return !KeyboardInputMixin.pause() && KeyboardInputMixin.reset().adjustForward(pressed);
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=1)})
    private boolean visualmod$backward(boolean pressed) {
        return !KeyboardInputMixin.pause() && KeyboardInputMixin.reset().adjustBackward(pressed);
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=2)})
    private boolean visualmod$left(boolean pressed) {
        return !KeyboardInputMixin.pause() && pressed;
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=3)})
    private boolean visualmod$right(boolean pressed) {
        return !KeyboardInputMixin.pause() && pressed;
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=4)})
    private boolean visualmod$jump(boolean pressed) {
        return !KeyboardInputMixin.pause() && pressed;
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=5)})
    private boolean visualmod$shift(boolean pressed) {
        return !KeyboardInputMixin.pause() && pressed;
    }

    @ModifyExpressionValue(method={"tick"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal=6)})
    private boolean visualmod$sprint(boolean pressed) {
        return !KeyboardInputMixin.pause() && KeyboardInputMixin.reset().adjustSprint(pressed);
    }

    @Unique
    private static boolean pause() {
        return ModuleManager.CLICK_ACTIONS.pauseMovementInput() || ModuleManager.AUTO_SWAP.pauseMovementInput();
    }

    @Unique
    private static SprintResetter reset() {
        return ModuleManager.KILL_AURA.isEnabled() ? ModuleManager.KILL_AURA.sprintReset : ModuleManager.TRIGGER_BOT.sprintReset;
    }
}

