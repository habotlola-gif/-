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
import com.visualmod.module.Ambience;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.WorldProjection;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

public final class AmbienceRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/rain_droplets")).withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/rain_droplets")).withUniform("Globals", UniformType.UNIFORM_BUFFER).withUniform("DropletInfo", UniformType.UNIFORM_BUFFER).withSampler("SceneSampler").withBlend(BlendFunction.TRANSLUCENT).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final int UBO_SIZE = new Std140SizeCalculator().putFloat().putFloat().get();
    private static final RenderPipeline REFLECTION_PIPELINE = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"pipeline/wet_reflections")).withVertexShader("core/screenquad").withFragmentShader(Identifier.fromNamespaceAndPath((String)"visualmod", (String)"core/wet_reflections")).withUniform("Globals", UniformType.UNIFORM_BUFFER).withUniform("ReflectionInfo", UniformType.UNIFORM_BUFFER).withSampler("SceneSampler").withSampler("DepthSampler").withBlend(BlendFunction.TRANSLUCENT).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final int REFLECTION_UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putMat4f().putVec4().putVec4().putVec4().get();
    private static final double NOISE_WRAP = 4096.0;
    private static @Nullable RenderTarget sceneCopy;
    private static @Nullable MappableRingBuffer ubo;
    private static @Nullable MappableRingBuffer reflectionUbo;
    private static final long START;
    private static final Vector4f HORIZON;

    private AmbienceRenderer() {
    }

    public static void setHorizonColor(Vector4f color) {
        HORIZON.set((Vector4fc)color);
    }

    private static float seconds() {
        return (float)((double)(System.nanoTime() - START) / 1.0E9 % 3600.0);
    }

    private static RenderTarget copyScene(RenderTarget main, CommandEncoder encoder) {
        if (sceneCopy == null) {
            sceneCopy = new TextureTarget("visualmod scene copy", main.width, main.height, false);
        } else if (AmbienceRenderer.sceneCopy.width != main.width || AmbienceRenderer.sceneCopy.height != main.height) {
            sceneCopy.resize(main.width, main.height);
        }
        encoder.copyTextureToTexture(main.getColorTexture(), sceneCopy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        return sceneCopy;
    }

    public static void renderWetReflections() {
        Ambience ambience = ModuleManager.AMBIENCE;
        float wetness = ambience.wetness();
        Minecraft client = Minecraft.getInstance();
        if (wetness <= 0.001f || !WorldProjection.isValid() || client.level == null) {
            return;
        }
        RenderTarget main = client.getMainRenderTarget();
        if (main.getDepthTextureView() == null) {
            return;
        }
        if (reflectionUbo == null) {
            reflectionUbo = new MappableRingBuffer(() -> "visualmod reflections UBO", 130, REFLECTION_UBO_SIZE);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderTarget scene = AmbienceRenderer.copyScene(main, encoder);
        Matrix4f invProjection = new Matrix4f(WorldProjection.projection()).invert();
        Matrix4f invView = new Matrix4f(WorldProjection.view()).invert();
        Vec3 camera = WorldProjection.cameraPos();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(reflectionUbo.currentBuffer(), false, true);){
            Std140Builder.intoBuffer((ByteBuffer)view.data()).putMat4f((Matrix4fc)invProjection).putMat4f((Matrix4fc)invView).putMat4f(WorldProjection.viewProjection()).putVec4(AmbienceRenderer.HORIZON.x, AmbienceRenderer.HORIZON.y, AmbienceRenderer.HORIZON.z, 1.0f).putVec4((float)(camera.x % 4096.0), (float)camera.y, (float)(camera.z % 4096.0), AmbienceRenderer.seconds()).putVec4(wetness, ambience.puddles.getFloat() / 100.0f, ambience.reflections.getFloat() / 100.0f, ambience.rippleStrength(client));
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "visualmod wet reflections", main.getColorTextureView(), OptionalInt.empty());){
            pass.setPipeline(REFLECTION_PIPELINE);
            RenderSystem.bindDefaultUniforms((RenderPass)pass);
            pass.setUniform("ReflectionInfo", reflectionUbo.currentBuffer());
            pass.bindTexture("SceneSampler", scene.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
        reflectionUbo.rotate();
    }

    public static void renderDroplets() {
        float intensity = ModuleManager.AMBIENCE.dropletIntensity();
        if (intensity <= 0.001f) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (ubo == null) {
            ubo = new MappableRingBuffer(() -> "visualmod droplets UBO", 130, UBO_SIZE);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderTarget scene = AmbienceRenderer.copyScene(main, encoder);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(ubo.currentBuffer(), false, true);){
            Std140Builder.intoBuffer((ByteBuffer)view.data()).putFloat(intensity).putFloat(AmbienceRenderer.seconds());
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "visualmod rain droplets", main.getColorTextureView(), OptionalInt.empty());){
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms((RenderPass)pass);
            pass.setUniform("DropletInfo", ubo.currentBuffer());
            pass.bindTexture("SceneSampler", scene.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        ubo.rotate();
    }

    static {
        START = System.nanoTime();
        HORIZON = new Vector4f(0.6f, 0.7f, 0.9f, 1.0f);
    }
}

