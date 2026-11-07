package com.visualmod.render;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

public final class WorldProjection {
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static boolean valid;

    private WorldProjection() {
    }

    public static void capture(Matrix4f view, Matrix4f projection, Vec3 camera) {
        VIEW.set((Matrix4fc)view);
        PROJECTION.set((Matrix4fc)projection);
        VIEW_PROJECTION.set((Matrix4fc)projection).mul((Matrix4fc)view);
        cameraPos = camera;
        valid = true;
    }

    public static boolean isValid() {
        return valid;
    }

    public static Matrix4fc view() {
        return VIEW;
    }

    public static Matrix4fc projection() {
        return PROJECTION;
    }

    public static Matrix4fc viewProjection() {
        return VIEW_PROJECTION;
    }

    public static Vec3 cameraPos() {
        return cameraPos;
    }

    public static float @Nullable [] toScreen(Vec3 world) {
        if (!valid) {
            return null;
        }
        Vector4f clip = new Vector4f((float)(world.x - WorldProjection.cameraPos.x), (float)(world.y - WorldProjection.cameraPos.y), (float)(world.z - WorldProjection.cameraPos.z), 1.0f).mul((Matrix4fc)VIEW_PROJECTION);
        if (clip.w <= 0.05f) {
            return null;
        }
        Window window = Minecraft.getInstance().getWindow();
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        return new float[]{(ndcX * 0.5f + 0.5f) * (float)window.getWidth(), (0.5f - ndcY * 0.5f) * (float)window.getHeight()};
    }
}

