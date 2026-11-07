package com.visualmod.render;

import com.visualmod.gui.UiRenderer;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.TargetEsp;
import com.visualmod.render.TargetTracker;
import com.visualmod.render.WorldProjection;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class TargetEspRenderer {
    private static final double GHOST_SIZE = 0.2;
    private static final double TRAIL_SECONDS = 0.6;
    private static final int TRAIL_SAMPLES = 48;
    private static float alpha;
    private static @Nullable LivingEntity shown;
    private static long lastFrame;
    private static final long START;
    private static final int ARROW_POINTS = 14;
    private static final int RING_POINTS = 128;

    private TargetEspRenderer() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        TargetEsp settings = ModuleManager.TARGET_ESP;
        Minecraft client = Minecraft.getInstance();
        if (!settings.isEnabled() || client.player == null || client.options.hideGui || !WorldProjection.isValid()) {
            alpha = 0.0f;
            shown = null;
            return;
        }
        long now = System.nanoTime();
        float seconds = Math.min((float)(now - lastFrame) / 1.0E9f, 0.1f);
        lastFrame = now;
        LivingEntity target = TargetTracker.current();
        if (target != null) {
            shown = target;
        }
        if ((alpha += ((target != null ? 1.0f : 0.0f) - alpha) * (1.0f - (float)Math.exp(-seconds * 10.0f))) < 0.01f || shown == null || !shown.isAlive()) {
            if (alpha < 0.01f) {
                shown = null;
            }
            return;
        }
        LivingEntity entity = shown;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        Vec3 base = entity.getPosition(partialTick);
        double time = (double)(now - START) / 1.0E9 * settings.speed.get();
        double fov = Math.toRadians(((Integer)client.options.fov().get()).intValue());
        double pixelsPerUnit = (double)client.getWindow().getHeight() / (2.0 * Math.tan(fov / 2.0));
        Vec3 camera = WorldProjection.cameraPos();
        float glowStrength = settings.glow.getFloat() / 100.0f;
        int color = settings.color.rgb();
        UiRenderer ui = UiRenderer.begin(graphics);
        try {
            ui.setOpacity(alpha);
            if (settings.isArrows()) {
                TargetEspRenderer.renderArrows(ui, settings, entity, base, time, camera, color, glowStrength);
            } else if (settings.isRing()) {
                TargetEspRenderer.renderRing(ui, settings, entity, base, time, color, glowStrength);
            } else if (settings.isCrystals()) {
                boolean visible = client.player != null && client.player.hasLineOfSight(entity);
                CrystalsEsp.render(ui, entity, base, settings.speed.getFloat(), settings.size.getFloat() / 100.0f,
                        glowStrength, camera, pixelsPerUnit, color, alpha, partialTick, visible);
            } else if (settings.isLightning()) {
                LightningEsp.render(ui, settings, entity, base, time, color, partialTick, alpha);
            } else {
                TargetEspRenderer.renderGhosts(ui, settings, entity, base, time, pixelsPerUnit, camera, color, glowStrength);
            }
        }
        finally {
            ui.setOpacity(1.0f);
            ui.end();
        }
    }

    private static void renderGhosts(UiRenderer ui, TargetEsp settings, LivingEntity entity, Vec3 base, double time, double pixelsPerUnit, Vec3 camera, int color, float glowStrength) {
        double height = entity.getBbHeight();
        double radius = Math.max(0.75, (double)entity.getBbWidth() * 1.3);
        int ghosts = settings.ghosts.getInt();
        double trailSeconds = 0.6 * settings.trailLength.get() / 100.0;
        double worldSize = 0.2 * settings.size.get() / 100.0;
        float[][] points = new float[49][];
        for (int g = 0; g < ghosts; ++g) {
            double phase = (double)g * Math.PI * 2.0 / (double)ghosts;
            for (int k = 0; k <= 48; ++k) {
                float fade = 1.0f - (float)k / 48.0f;
                double t = time - trailSeconds * (double)k / 48.0;
                Vec3 point = TargetEspRenderer.ghostPosition(base, height, radius, t, phase, g);
                float[] screen = WorldProjection.toScreen(point);
                if (screen == null) {
                    points[k] = null;
                    continue;
                }
                double distance = Math.max(0.3, point.distanceTo(camera));
                double width = worldSize * pixelsPerUnit / distance * (0.12 + 0.88 * Math.pow(fade, 0.9));
                points[k] = new float[]{screen[0], screen[1], (float)width, fade};
            }
            TargetEspRenderer.drawGhost(ui, points, color, glowStrength);
        }
    }

    private static void drawGhost(UiRenderer ui, float[][] points, int color, float glowStrength) {
        int bodyColor = UiRenderer.lerpColor(color, -1, 0.3f);
        if (glowStrength > 0.0f) {
            TargetEspRenderer.stroke(ui, points, color, 2.2f, 2.0f, Math.min(0.9f, 0.32f * glowStrength), 1.0f);
        }
        TargetEspRenderer.stroke(ui, points, bodyColor, 0.9f, 3.0f, 0.8f, 1.2f);
        float[] head = points[0];
        if (head != null && glowStrength > 0.0f) {
            int halo = Math.max(4, Math.round(head[2] * 2.6f));
            int a = Math.min(255, Math.round(48.0f * glowStrength));
            ui.blurCircle(Math.round(head[0]) - halo / 2, Math.round(head[1]) - halo / 2, halo, halo / 2, a << 24 | color & 0xFFFFFF);
        }
    }

    private static Vec3 ghostPosition(Vec3 base, double height, double radius, double t, double phase, int index) {
        double angle = t * 2.4 + phase;
        double wobble = Math.sin(t * 1.7 + (double)index * 2.1);
        double y = height * (0.5 + 0.42 * Math.sin(t * 2.0 + phase * 1.5)) + wobble * 0.1;
        double r = radius * (0.92 + 0.12 * Math.sin(t * 3.1 + (double)index));
        return base.add(Math.cos(angle) * r, y, Math.sin(angle) * r);
    }

    private static void renderArrows(UiRenderer ui, TargetEsp settings, LivingEntity entity, Vec3 base, double time, Vec3 camera, int color, float glowStrength) {
        double radius = Math.max(0.72, (double)entity.getBbWidth() * 0.92) * settings.size.get() / 100.0;
        double faceAngle = Math.atan2(camera.z - base.z, camera.x - base.x);
        float thickness = Math.max(2.5f, 2.6f * (float)ui.guiScale() * settings.thickness.getFloat() / 100.0f);
        for (int shard = 0; shard < 4; ++shard) {
            double center = faceAngle + time * 0.8 + (double)shard * Math.PI / 2.0;
            float[][] points = new float[15][];
            for (int i = 0; i <= 14; ++i) {
                float[] fArray;
                double fraction = (double)i / 14.0;
                double angle = center + (fraction - 0.5) * 0.75;
                double y = (double)entity.getBbHeight() * (0.52 + 0.27 * Math.sin(angle + (double)shard * 0.7 + time * 0.35));
                float[] screen = WorldProjection.toScreen(base.add(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
                float taper = (float)Math.pow(Math.sin(Math.PI * fraction), 0.4);
                float facing = (float)(0.65 + 0.35 * Math.max(0.0, Math.cos(angle - faceAngle)));
                if (screen == null) {
                    fArray = null;
                } else {
                    float[] fArray2 = new float[3];
                    fArray2[0] = screen[0];
                    fArray2[1] = screen[1];
                    fArray = fArray2;
                    fArray2[2] = taper * facing;
                }
                points[i] = fArray;
            }
            TargetEspRenderer.drawLuminousLine(ui, points, thickness, color, glowStrength);
        }
    }

    private static void renderRing(UiRenderer ui, TargetEsp settings, LivingEntity entity, Vec3 base, double time, int color, float glowStrength) {
        double radius = Math.max(0.75, (double)entity.getBbWidth() * 1.08) * settings.size.get() / 100.0;
        double y = (double)entity.getBbHeight() * (0.54 + 0.12 * Math.sin(time * 1.1));
        float thickness = Math.max(2.0f, 2.1f * (float)ui.guiScale() * settings.thickness.getFloat() / 100.0f);
        Vec3 camera = WorldProjection.cameraPos();
        double faceAngle = Math.atan2(camera.z - base.z, camera.x - base.x);
        float[] silhouette = TargetEspRenderer.horizontalSilhouette(base, (double)entity.getBbWidth() * 0.5, y);
        float[][] points = new float[129][];
        for (int i = 0; i <= 128; ++i) {
            float[] fArray;
            double angle = (double)i * Math.PI * 2.0 / 128.0;
            float[] p = WorldProjection.toScreen(base.add(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
            float front = (float)Math.cos(angle - faceAngle);
            float sweep = (float)Math.pow(Math.max(0.0, Math.cos(angle - time * 1.35)), 8.0);
            float intensity = Math.min(1.0f, 0.72f + 0.24f * Math.max(0.0f, front) + 0.22f * sweep);
            if (p != null && silhouette != null && front < 0.0f) {
                float outside = Math.max(silhouette[0] - p[0], p[0] - silhouette[1]);
                float visible = Math.max(0.0f, Math.min(1.0f, (outside + thickness * 2.0f) / (thickness * 6.0f)));
                float back = Math.min(1.0f, -front / 0.3f);
                intensity *= 1.0f - back * (1.0f - visible);
            }
            if (p == null) {
                fArray = null;
            } else {
                float[] fArray2 = new float[3];
                fArray2[0] = p[0];
                fArray2[1] = p[1];
                fArray = fArray2;
                fArray2[2] = intensity;
            }
            points[i] = fArray;
        }
        TargetEspRenderer.drawLuminousLine(ui, points, thickness, color, glowStrength);
    }

    private static float @Nullable [] horizontalSilhouette(Vec3 base, double halfWidth, double y) {
        float[] fArray;
        float min = Float.MAX_VALUE;
        float max = -3.4028235E38f;
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                float[] p = WorldProjection.toScreen(base.add((double)x * halfWidth, y, (double)z * halfWidth));
                if (p == null) continue;
                min = Math.min(min, p[0]);
                max = Math.max(max, p[0]);
            }
        }
        if (min == Float.MAX_VALUE) {
            fArray = null;
        } else {
            float[] fArray2 = new float[2];
            fArray2[0] = min;
            fArray = fArray2;
            fArray2[1] = max;
        }
        return fArray;
    }

    static void drawLuminousLine(UiRenderer ui, float[][] points, float thickness, int color, float glowStrength) {
        int coreColor = -1;
        if (glowStrength > 0.0f) {
            ui.ribbon(points, thickness * 5.0f, true, TargetEspRenderer.withAlpha(color, 0.48f * glowStrength));
            ui.ribbon(points, thickness * 2.8f, true, TargetEspRenderer.withAlpha(coreColor, 0.68f * glowStrength));
        }
        ui.ribbon(points, thickness, false, TargetEspRenderer.withAlpha(coreColor, 0.98f));
    }

    private static int withAlpha(int color, float alpha) {
        return Math.min(255, Math.max(0, Math.round(alpha * 255.0f))) << 24 | color & 0xFFFFFF;
    }

    private static void stroke(UiRenderer ui, float[][] points, int color, float sizeMul, float blurDiv, float opacity, float fadePower) {
        for (int k = points.length - 1; k >= 0; --k) {
            float[] p = points[k];
            if (p == null) continue;
            float size = Math.max(2.0f, p[2] * sizeMul);
            float spacing = TargetEspRenderer.spacing(points, k);
            float overlap = Math.max(1.0f, Math.min(64.0f, size / Math.max(0.5f, spacing)));
            float target = opacity * (float)Math.pow(p[3], fadePower);
            if (target <= 0.0f) continue;
            float perCircle = 1.0f - (float)Math.pow(1.0f - Math.min(0.99f, target), 1.0f / overlap);
            int a = Math.max(1, Math.min(255, Math.round(perCircle * 255.0f)));
            int s = Math.round(size);
            ui.blurCircle(Math.round(p[0]) - s / 2, Math.round(p[1]) - s / 2, s, Math.max(1, Math.round(size / blurDiv)), a << 24 | color & 0xFFFFFF);
        }
    }

    private static float spacing(float[][] points, int k) {
        float sum = 0.0f;
        int count = 0;
        if (k > 0 && points[k - 1] != null) {
            sum += (float)Math.hypot(points[k][0] - points[k - 1][0], points[k][1] - points[k - 1][1]);
            ++count;
        }
        if (k + 1 < points.length && points[k + 1] != null) {
            sum += (float)Math.hypot(points[k][0] - points[k + 1][0], points[k][1] - points[k + 1][1]);
            ++count;
        }
        return count == 0 ? Float.MAX_VALUE : sum / (float)count;
    }

    static {
        lastFrame = System.nanoTime();
        START = System.nanoTime();
    }
}

