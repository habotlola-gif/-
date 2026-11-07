package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={WaterDropParticle.class})
public abstract class WaterDropParticleMixin
extends SingleQuadParticle {
    protected WaterDropParticleMixin(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite) {
        super(level, x, y, z, sprite);
    }

    @Inject(method={"<init>"}, at={@At(value="TAIL")})
    private void visualmod$softSplash(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (this.visualmod$isSoftSplash()) {
            this.quadSize *= 0.55f;
            this.setColor(0.86f, 0.92f, 1.0f);
            this.setAlpha(0.38f);
        }
    }

    @ModifyReturnValue(method={"getLayer"}, at={@At(value="RETURN")})
    private SingleQuadParticle.Layer visualmod$translucentSplash(SingleQuadParticle.Layer layer) {
        return this.visualmod$isSoftSplash() ? SingleQuadParticle.Layer.TRANSLUCENT : layer;
    }

    @Unique
    private boolean visualmod$isSoftSplash() {
        WaterDropParticleMixin self = this;
        return ((Object)((Object)self)).getClass() == WaterDropParticle.class && ModuleManager.AMBIENCE.usesSoftRain();
    }
}

