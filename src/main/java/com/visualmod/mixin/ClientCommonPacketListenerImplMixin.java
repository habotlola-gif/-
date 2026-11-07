package com.visualmod.mixin;

import com.visualmod.module.ModuleManager;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Пока AirStuck держит игрока в воздухе, пакеты движения не уходят на сервер. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void visualmod$airStuck(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundMovePlayerPacket && ModuleManager.AIR_STUCK.blockPackets()) {
            ci.cancel();
        }
    }
}
