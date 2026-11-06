package com.visualmod.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.ShaderEsp;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class ShaderEspRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/shader_esp")).withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/shader_esp")).withUniform("Globals", UniformType.UNIFORM_BUFFER).withUniform("EspInfo", UniformType.UNIFORM_BUFFER).withSampler("InSampler").withBlend(BlendFunction.TRANSLUCENT).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putFloat().putFloat().putFloat().putInt().get();
    private static final double PHASE_PER_SECOND = 0.7853981633974483;
    private static @Nullable MappableRingBuffer ubo;
    private static double phase;
    private static long lastFrameNanos;

    private ShaderEspRenderer() {
    }

    public static boolean isActive() {
        return ModuleManager.SHADER_ESP.isEnabled();
    }

    public static void composite(RenderTarget outlineTarget) {
        if (ubo == null) {
            ubo = new MappableRingBuffer(() -> "visualmod esp UBO", 130, UBO_SIZE);
        }
        ShaderEsp settings = ModuleManager.SHADER_ESP;
        long now = System.nanoTime();
        double seconds = Math.min((double)(now - lastFrameNanos) / 1.0E9, 0.25);
        lastFrameNanos = now;
        phase = (phase + seconds * 0.7853981633974483 * settings.speed.get()) % (Math.PI * 2);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int a = settings.colorA.rgb();
        int b = settings.colorB.rgb();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(ubo.currentBuffer(), false, true);){
            Std140Builder.intoBuffer((ByteBuffer)view.data()).putVec4((float)(a >> 16 & 0xFF) / 255.0f, (float)(a >> 8 & 0xFF) / 255.0f, (float)(a & 0xFF) / 255.0f, 1.0f).putVec4((float)(b >> 16 & 0xFF) / 255.0f, (float)(b >> 8 & 0xFF) / 255.0f, (float)(b & 0xFF) / 255.0f, 1.0f).putFloat(settings.fill.getFloat() / 100.0f).putFloat(settings.glow.getFloat()).putFloat((float)phase).putInt(settings.colorMode.getIndex());
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        try (RenderPass pass = encoder.createRenderPass(() -> "visualmod shader esp", main.getColorTextureView(), OptionalInt.empty());){
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms((RenderPass)pass);
            pass.setUniform("EspInfo", ubo.currentBuffer());
            pass.bindTexture("InSampler", outlineTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
        ubo.rotate();
    }

    static {
        lastFrameNanos = System.nanoTime();
    }
}

