package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.visualmod.FriendManager;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={EntityRenderer.class})
public abstract class EntityRendererMixin {
    private static final int VISUALMOD_FRIEND_MARKER = 15862767;

    @ModifyExpressionValue(method={"extractRenderState"}, at={@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getTeamColor()I")})
    private int visualmod$friendOutlineColor(int color, @Local(argsOnly=true) Entity entity) {
        if (entity instanceof Player) {
            Player player = (Player)entity;
            if (ModuleManager.SHADER_ESP.shouldHighlight(entity) && FriendManager.isFriend(player.getGameProfile().name())) {
                return 15862767;
            }
        }
        return color;
    }

    @ModifyExpressionValue(method={"extractRenderState"}, at={@At(value="INVOKE", target="Lnet/minecraft/client/renderer/entity/EntityRenderer;shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z")})
    private boolean visualmod$hideVanillaNameTag(boolean show, @Local(argsOnly=true) Entity entity) {
        return show && !ModuleManager.NAME_TAGS.shouldTag(entity);
    }
}

