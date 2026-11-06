package com.visualmod.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value={LivingEntity.class})
public interface LivingEntityAccessor {
    @Accessor(value="noJumpDelay")
    public int visualmod$getNoJumpDelay();

    @Accessor(value="noJumpDelay")
    public void visualmod$setNoJumpDelay(int var1);
}

