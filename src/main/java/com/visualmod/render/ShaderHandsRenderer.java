package com.visualmod.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.ShaderHands;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class ShaderHandsRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/shader_hands")).withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/shader_hands")).withUniform("Globals", UniformType.UNIFORM_BUFFER).withUniform("HandsInfo", UniformType.UNIFORM_BUFFER).withSampler("InSampler").withSampler("TrailSampler").withBlend(BlendFunction.TRANSLUCENT).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final RenderPipeline TRAIL_PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/hands_trail")).withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/hands_trail")).withUniform("Globals", UniformType.UNIFORM_BUFFER).withUniform("TrailInfo", UniformType.UNIFORM_BUFFER).withSampler("HandSampler").withSampler("TrailSampler").withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putFloat().putFloat().putFloat().putInt().putFloat().putInt().get();
    private static final int TRAIL_UBO_SIZE = new Std140SizeCalculator().putFloat().putFloat().putFloat().putFloat().get();
    private static final double PHASE_PER_SECOND = 0.7853981633974483;
    private static final float TRAIL_LIFE = 0.42f;
    private static final float TRAIL_RISE = 0.5f;
    private static @Nullable RenderTarget handTarget;
    private static final @Nullable RenderTarget[] trailTargets;
    private static int trailCurrent;
    private static boolean trailNeedsClear;
    private static @Nullable MappableRingBuffer ubo;
    private static @Nullable MappableRingBuffer trailUbo;
    private static boolean redirecting;
    private static double phase;
    private static long lastFrameNanos;
    private static final long START;

    private ShaderHandsRenderer() {
    }

    public static @Nullable RenderTarget redirectTarget() {
        return redirecting ? handTarget : null;
    }

    public static void begin() {
        if (!ModuleManager.SHADER_HANDS.isEnabled()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (handTarget == null) {
            handTarget = new TextureTarget("visualmod hands", main.width, main.height, true);
        } else if (ShaderHandsRenderer.handTarget.width != main.width || ShaderHandsRenderer.handTarget.height != main.height) {
            handTarget.resize(main.width, main.height);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(handTarget.getColorTexture(), 0, handTarget.getDepthTexture(), 1.0);
        redirecting = true;
    }

    public static boolean isRedirecting() {
        return redirecting;
    }

    public static void end() {
        RenderTarget trailTarget;
        redirecting = false;
        if (handTarget == null) {
            return;
        }
        if (ubo == null) {
            ubo = new MappableRingBuffer(() -> "visualmod hands UBO", 130, UBO_SIZE);
        }
        ShaderHands settings = ModuleManager.SHADER_HANDS;
        long now = System.nanoTime();
        double seconds = Math.min((double)(now - lastFrameNanos) / 1.0E9, 0.25);
        lastFrameNanos = now;
        phase = (phase + seconds * 0.7853981633974483 * settings.speed.get()) % (Math.PI * 2);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        boolean trail = settings.trail.isOn();
        RenderTarget renderTarget = trailTarget = trail ? ShaderHandsRenderer.updateTrail(encoder, main, (float)seconds, settings) : null;
        if (!trail) {
            trailNeedsClear = true;
        }
        int a = settings.colorA.rgb();
        int b = settings.colorB.rgb();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(ubo.currentBuffer(), false, true);){
            Std140Builder.intoBuffer((ByteBuffer)view.data()).putVec4((float)(a >> 16 & 0xFF) / 255.0f, (float)(a >> 8 & 0xFF) / 255.0f, (float)(a & 0xFF) / 255.0f, 1.0f).putVec4((float)(b >> 16 & 0xFF) / 255.0f, (float)(b >> 8 & 0xFF) / 255.0f, (float)(b & 0xFF) / 255.0f, 1.0f).putFloat(settings.fill.getFloat() / 100.0f).putFloat(settings.glow.getFloat()).putFloat((float)phase).putInt(settings.mode.getIndex()).putFloat(trailTarget != null ? settings.trailBrightness.getFloat() / 100.0f : 0.0f).putInt(settings.trailPalette.getIndex());
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "visualmod shader hands", main.getColorTextureView(), OptionalInt.empty());){
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms((RenderPass)pass);
            pass.setUniform("HandsInfo", ubo.currentBuffer());
            pass.bindTexture("InSampler", handTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            RenderTarget trailSource = trailTarget != null ? trailTarget : handTarget;
            pass.bindTexture("TrailSampler", trailSource.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        ubo.rotate();
    }

    private static RenderTarget updateTrail(CommandEncoder encoder, RenderTarget main, float seconds, ShaderHands settings) {
        for (int i = 0; i < 2; ++i) {
            if (trailTargets[i] == null) {
                ShaderHandsRenderer.trailTargets[i] = new TextureTarget("visualmod hands trail " + i, main.width, main.height, false);
                trailNeedsClear = true;
                continue;
            }
            if (ShaderHandsRenderer.trailTargets[i].width == main.width && ShaderHandsRenderer.trailTargets[i].height == main.height) continue;
            trailTargets[i].resize(main.width, main.height);
            trailNeedsClear = true;
        }
        if (trailNeedsClear) {
            encoder.clearColorTexture(trailTargets[0].getColorTexture(), 0);
            encoder.clearColorTexture(trailTargets[1].getColorTexture(), 0);
            trailNeedsClear = false;
        }
        if (trailUbo == null) {
            trailUbo = new MappableRingBuffer(() -> "visualmod hands trail UBO", 130, TRAIL_UBO_SIZE);
        }
        float life = 0.42f * settings.trailLength.getFloat() / 100.0f;
        float decay = (float)Math.exp(-3.5 * (double)seconds / (double)life);
        float time = (float)((double)(System.nanoTime() - START) / 1.0E9 % 3600.0);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(trailUbo.currentBuffer(), false, true);){
            Std140Builder.intoBuffer((ByteBuffer)view.data()).putFloat(decay).putFloat(0.5f * seconds).putFloat(time).putFloat(1.0f);
        }
        RenderTarget previous = trailTargets[trailCurrent];
        RenderTarget next = trailTargets[1 - trailCurrent];
        try (RenderPass pass = encoder.createRenderPass(() -> "visualmod hands trail", next.getColorTextureView(), OptionalInt.empty());){
            pass.setPipeline(TRAIL_PIPELINE);
            RenderSystem.bindDefaultUniforms((RenderPass)pass);
            pass.setUniform("TrailInfo", trailUbo.currentBuffer());
            pass.bindTexture("HandSampler", handTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.bindTexture("TrailSampler", previous.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        trailUbo.rotate();
        trailCurrent = 1 - trailCurrent;
        return next;
    }

    static {
        trailTargets = new RenderTarget[2];
        trailNeedsClear = true;
        lastFrameNanos = System.nanoTime();
        START = System.nanoTime();
    }
}

