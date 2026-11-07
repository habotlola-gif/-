package com.visualmod.gui;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

public record ShapeRenderState(Matrix3x2fc pose, int x, int y, int width, int height, int radius, int mode, int color, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState
{
    public static final int MODE_FILL = 0;
    public static final int MODE_HUE = -1;
    public static final int MODE_SATURATION_VALUE = -2;
    public static final int MODE_HUE_FULL = -3;
    private static final VertexFormat FORMAT = VertexFormat.builder().add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR).add("UV0", VertexFormatElement.UV0).add("UV1", VertexFormatElement.UV1).add("UV2", VertexFormatElement.UV2).build();
    public static final RenderPipeline PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[]{RenderPipelines.MATRICES_PROJECTION_SNIPPET}).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/ui_shape")).withVertexShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/ui_shape")).withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/ui_shape")).withBlend(BlendFunction.TRANSLUCENT).withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).build();

    public static ShapeRenderState create(Matrix3x2fc pose, int x, int y, int width, int height, int radius, int mode, int color, @Nullable ScreenRectangle scissor) {
        int margin = ShapeRenderState.margin(mode);
        ScreenRectangle area = new ScreenRectangle(x - margin, y - margin, width + margin * 2, height + margin * 2).transformMaxBounds(pose);
        ScreenRectangle bounds = scissor != null ? scissor.intersection(area) : area;
        return new ShapeRenderState(pose, x, y, width, height, radius, mode, color, scissor, bounds);
    }

    private static int margin(int mode) {
        return 1 + Math.max(0, mode);
    }

    public void buildVertices(VertexConsumer consumer) {
        int margin = ShapeRenderState.margin(this.mode);
        float x0 = this.x - margin;
        float y0 = this.y - margin;
        float x1 = this.x + this.width + margin;
        float y1 = this.y + this.height + margin;
        float cx = (float)this.x + (float)this.width * 0.5f;
        float cy = (float)this.y + (float)this.height * 0.5f;
        this.vertex(consumer, x0, y0, cx, cy);
        this.vertex(consumer, x0, y1, cx, cy);
        this.vertex(consumer, x1, y1, cx, cy);
        this.vertex(consumer, x1, y0, cx, cy);
    }

    private void vertex(VertexConsumer consumer, float vx, float vy, float cx, float cy) {
        consumer.addVertexWith2DPose(this.pose, vx, vy).setColor(this.color).setUv(vx - cx, vy - cy).setUv1(this.width, this.height).setUv2(this.radius, this.mode);
    }

    public RenderPipeline pipeline() {
        return PIPELINE;
    }

    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }
}

