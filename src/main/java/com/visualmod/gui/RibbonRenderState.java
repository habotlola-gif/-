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

public record RibbonRenderState(Matrix3x2fc pose, float[][] points, float width, boolean glow, int color, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState
{
    private static final VertexFormat FORMAT = VertexFormat.builder().add("Position", VertexFormatElement.POSITION).add("Color", VertexFormatElement.COLOR).add("UV0", VertexFormatElement.UV0).build();
    private static final RenderPipeline PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[]{RenderPipelines.MATRICES_PROJECTION_SNIPPET}).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/ui_ribbon")).withVertexShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/ui_ribbon")).withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/ui_ribbon")).withBlend(BlendFunction.TRANSLUCENT).withVertexFormat(FORMAT, VertexFormat.Mode.QUADS).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).build();

    public static RibbonRenderState create(Matrix3x2fc pose, float[][] points, float width, boolean glow, int color, @Nullable ScreenRectangle scissor) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -3.4028235E38f;
        float maxY = -3.4028235E38f;
        for (float[] point : points) {
            if (point == null) continue;
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }
        if (minX == Float.MAX_VALUE) {
            return null;
        }
        int margin = Math.max(2, (int)Math.ceil(width * 0.5f + 2.0f));
        ScreenRectangle area = new ScreenRectangle((int)Math.floor(minX) - margin, (int)Math.floor(minY) - margin, (int)Math.ceil(maxX - minX) + margin * 2 + 1, (int)Math.ceil(maxY - minY) + margin * 2 + 1).transformMaxBounds(pose);
        ScreenRectangle bounds = scissor == null ? area : scissor.intersection(area);
        return new RibbonRenderState(pose, points, width, glow, color, scissor, bounds);
    }

    public void buildVertices(VertexConsumer consumer) {
        for (int i = 0; i < this.points.length - 1; ++i) {
            if (this.points[i] == null || this.points[i + 1] == null) continue;
            float[] a = this.edge(i);
            float[] b = this.edge(i + 1);
            if (a == null || b == null) continue;
            this.vertex(consumer, a, -1.0f);
            this.vertex(consumer, a, 1.0f);
            this.vertex(consumer, b, 1.0f);
            this.vertex(consumer, b, -1.0f);
        }
    }

    private float @Nullable [] edge(int i) {
        float dy;
        float[] prev;
        float[] point = this.points[i];
        if (point == null) {
            return null;
        }
        float[] next = i + 1 < this.points.length && this.points[i + 1] != null ? this.points[i + 1] : point;
        float dx = next[0] - (prev = i > 0 && this.points[i - 1] != null ? this.points[i - 1] : point)[0];
        float length = (float)Math.hypot(dx, dy = next[1] - prev[1]);
        if (length < 0.001f) {
            return null;
        }
        return new float[]{point[0], point[1], -dy / length, dx / length, point[2]};
    }

    private void vertex(VertexConsumer consumer, float[] edge, float side) {
        float half = this.width * 0.5f;
        int alpha = Math.max(0, Math.min(255, Math.round((float)(this.color >>> 24) * edge[4])));
        consumer.addVertexWith2DPose(this.pose, edge[0] + edge[2] * half * side, edge[1] + edge[3] * half * side).setColor(alpha << 24 | this.color & 0xFFFFFF).setUv(this.glow ? -this.width : this.width, side);
    }

    public RenderPipeline pipeline() {
        return PIPELINE;
    }

    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }
}

