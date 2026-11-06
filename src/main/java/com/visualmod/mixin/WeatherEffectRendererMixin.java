package com.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.visualmod.module.ModuleManager;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value={WeatherEffectRenderer.class})
public abstract class WeatherEffectRendererMixin {
    @Unique
    private static final Identifier SOFT_RAIN = Identifier.fromNamespaceAndPath((String)"visualmod", (String)"textures/environment/rain_soft.png");

    @ModifyReturnValue(method={"getPrecipitationAt"}, at={@At(value="RETURN")})
    private Biome.Precipitation visualmod$customPrecipitation(Biome.Precipitation precipitation) {
        return ModuleManager.AMBIENCE.adjustPrecipitation(precipitation);
    }

    @ModifyArg(method={"render"}, at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/rendertype/RenderTypes;weather(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;", ordinal=0), index=0)
    private Identifier visualmod$softRainTexture(Identifier texture) {
        return ModuleManager.AMBIENCE.usesSoftRain() ? SOFT_RAIN : texture;
    }
}

