package com.visualmod.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value={Minecraft.class})
public interface MinecraftAccessor {
    @Accessor(value="rightClickDelay")
    public int visualmod$getRightClickDelay();

    @Accessor(value="rightClickDelay")
    public void visualmod$setRightClickDelay(int var1);
}

