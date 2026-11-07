package com.visualmod.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value={GuiGraphics.class})
public interface GuiGraphicsAccessor {
    @Accessor(value="scissorStack")
    public GuiGraphics.ScissorStack visualmod$getScissorStack();
}

