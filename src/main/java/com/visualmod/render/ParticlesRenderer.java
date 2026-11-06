package com.visualmod.render;

import com.visualmod.gui.UiRenderer;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.Particles;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Частицы-звёздочки. Живут в мировых координатах, на экран проецируются через {@link WorldProjection}
 * и рисуются контуром скруглённой звезды (как на референсе) с мягким свечением, как остальные эффекты мода.
 */
public final class ParticlesRenderer {
    private static final int MAX_PARTICLES = 260;
    private static final int STAR_POINTS = 40;
    private static final Random RANDOM = new Random();
    private static final List<Star> STARS = new ArrayList<>();
    private static long lastNanos;
    private static double spawnBudgetMe, spawnBudgetTarget;
    private static final float[][] UNIT_STAR = buildUnitStar();

    private ParticlesRenderer() {
    }

    private static final class Star {
        double x, y, z, vx, vy, vz;
        float age, life, size, rot, spin, hue;
    }

    /** Скруглённая 5-лучевая звезда: радиус r(θ) = 0.66 + 0.34·cos(5θ), как у референса. Замкнутый контур. */
    private static float[][] buildUnitStar() {
        float[][] pts = new float[STAR_POINTS + 1][];
        for (int i = 0; i <= STAR_POINTS; i++) {
            double a = Math.PI * 2.0 * i / STAR_POINTS;
            double r = 0.66 + 0.34 * Math.cos(5.0 * a);
            pts[i] = new float[]{(float) (Math.sin(a) * r), (float) (-Math.cos(a) * r)}; // верхний луч смотрит вверх
        }
        return pts;
    }

    /** Вспышка при ударе: вызывается из TargetTracker.onAttack. */
    public static void onAttack(LivingEntity target) {
        Particles s = ModuleManager.PARTICLES;
        if (!s.isEnabled() || !s.onHit.isOn() || target == null) return;
        Vec3 c = target.position().add(0.0, target.getBbHeight() * 0.6, 0.0);
        for (int i = 0; i < 14; i++) {
            spawn(c.x, c.y, c.z, 1.0, true, s);
        }
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Particles s = ModuleManager.PARTICLES;
        Minecraft mc = Minecraft.getInstance();
        if (!s.isEnabled() || mc.player == null || mc.level == null || mc.options.hideGui || !WorldProjection.isValid()) {
            STARS.clear();
            spawnBudgetMe = spawnBudgetTarget = 0;
            lastNanos = 0;
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min((now - lastNanos) / 1.0e9f, 0.05f);
        lastNanos = now;
        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);

        // --- спавн ---
        double rate = s.amount.get();
        if (s.aroundMe.isOn()) {
            spawnBudgetMe += rate * 0.6 * dt;
            Vec3 p = mc.player.getPosition(partial);
            while (spawnBudgetMe >= 1.0) {
                spawnBudgetMe -= 1.0;
                double ang = RANDOM.nextDouble() * Math.PI * 2, rad = 0.8 + RANDOM.nextDouble() * 0.9;
                spawn(p.x + Math.cos(ang) * rad, p.y + RANDOM.nextDouble() * 2.0, p.z + Math.sin(ang) * rad, 1.0, false, s);
            }
        } else {
            spawnBudgetMe = 0;
        }
        LivingEntity target = TargetTracker.current();
        if (s.aroundTarget.isOn() && target != null) {
            spawnBudgetTarget += rate * dt;
            Vec3 p = target.getPosition(partial);
            while (spawnBudgetTarget >= 1.0) {
                spawnBudgetTarget -= 1.0;
                double ang = RANDOM.nextDouble() * Math.PI * 2, rad = target.getBbWidth() * 0.7 + 0.3 + RANDOM.nextDouble() * 0.5;
                spawn(p.x + Math.cos(ang) * rad, p.y + RANDOM.nextDouble() * target.getBbHeight(), p.z + Math.sin(ang) * rad, 1.0, false, s);
            }
        } else {
            spawnBudgetTarget = 0;
        }

        // --- обновление ---
        float riseMul = s.rise.getFloat() / 100.0f;
        for (int i = STARS.size() - 1; i >= 0; i--) {
            Star a = STARS.get(i);
            a.age += dt;
            if (a.age >= a.life) {
                STARS.remove(i);
                continue;
            }
            a.x += a.vx * dt;
            a.y += (a.vy + riseMul * 0.5) * dt;
            a.z += a.vz * dt;
            a.vx *= 1.0 - 0.6 * dt;
            a.vz *= 1.0 - 0.6 * dt;
            a.rot += a.spin * dt;
        }
        if (STARS.isEmpty()) return;

        // --- отрисовка (дальние первыми) ---
        Vec3 cam = WorldProjection.cameraPos();
        double fov = Math.toRadians(((Integer) mc.options.fov().get()).intValue());
        double pxPerUnit = (double) mc.getWindow().getHeight() / (2.0 * Math.tan(fov / 2.0));
        List<Star> order = new ArrayList<>(STARS);
        order.sort(Comparator.comparingDouble((Star a) -> -dist2(a, cam)));

        UiRenderer ui = UiRenderer.begin(graphics);
        try {
            float glow = s.glow.getFloat() / 100.0f;
            for (Star a : order) {
                float[] scr = WorldProjection.toScreen(new Vec3(a.x, a.y, a.z));
                if (scr == null) continue;
                double dist = Math.max(0.3, Math.sqrt(dist2(a, cam)));
                float r = (float) (a.size * pxPerUnit / dist);     // радиус звезды в пикселях
                if (r < 2.5f) continue;

                float fadeIn = Mth.clamp(a.age / 0.2f, 0.0f, 1.0f);
                float fadeOut = Mth.clamp((a.life - a.age) / 0.5f, 0.0f, 1.0f);
                float alpha = fadeIn * fadeOut;
                int color = s.rainbow.isOn()
                        ? java.awt.Color.HSBtoRGB((a.hue + a.age * 0.15f) % 1.0f, 0.65f, 0.97f) | 0xFF000000
                        : s.color.rgb();
                drawStar(ui, scr[0], scr[1], r * (0.75f + 0.25f * fadeOut), a.rot, color, alpha, s, glow);
            }
        } finally {
            ui.setOpacity(1.0f);
            ui.end();
        }
    }

    private static double dist2(Star a, Vec3 c) {
        double dx = a.x - c.x, dy = a.y - c.y, dz = a.z - c.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void drawStar(UiRenderer ui, float cx, float cy, float r, float rot, int color, float alpha, Particles s, float glow) {
        ui.setOpacity(Mth.clamp(alpha, 0.0f, 1.0f));
        float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
        float thick = Math.max(1.5f, r * 0.16f * s.thickness.getFloat() / 100.0f);
        if (s.fill.isOn()) {
            // заливка: несколько уменьшенных контуров поверх друг друга
            for (float k = 1.0f; k > 0.1f; k -= 0.18f) {
                ui.ribbon(starPoints(cx, cy, r * k, cos, sin), Math.max(2.0f, r * 0.30f), false, withAlpha(color, 0.85f));
            }
        } else {
            if (glow > 0.0f) {
                ui.ribbon(starPoints(cx, cy, r, cos, sin), thick * 3.2f, true, withAlpha(color, Math.min(0.9f, 0.40f * glow)));
            }
            ui.ribbon(starPoints(cx, cy, r, cos, sin), thick, false, withAlpha(UiRenderer.lerpColor(color, -1, 0.25f), 0.96f));
        }
        if (glow > 0.0f) {
            int halo = Math.max(4, Math.round(r * 1.6f));
            ui.blurCircle(Math.round(cx) - halo / 2, Math.round(cy) - halo / 2, halo, halo / 2, withAlpha(color, Math.min(0.5f, 0.16f * glow)));
        }
    }

    private static float[][] starPoints(float cx, float cy, float r, float cos, float sin) {
        float[][] out = new float[UNIT_STAR.length][];
        for (int i = 0; i < UNIT_STAR.length; i++) {
            float ux = UNIT_STAR[i][0], uy = UNIT_STAR[i][1];
            out[i] = new float[]{cx + (ux * cos - uy * sin) * r, cy + (ux * sin + uy * cos) * r, 1.0f};
        }
        return out;
    }

    private static int withAlpha(int color, float alpha) {
        return Math.min(255, Math.max(0, Math.round(alpha * 255.0f))) << 24 | color & 0xFFFFFF;
    }

    private static void spawn(double x, double y, double z, double scale, boolean burst, Particles s) {
        if (STARS.size() >= MAX_PARTICLES) return;
        Star a = new Star();
        a.x = x; a.y = y; a.z = z;
        float spd = s.speed.getFloat() / 100.0f;
        double ang = RANDOM.nextDouble() * Math.PI * 2;
        double horizontal = (burst ? 1.6 : 0.25) * spd * (0.5 + RANDOM.nextDouble());
        a.vx = Math.cos(ang) * horizontal;
        a.vz = Math.sin(ang) * horizontal;
        a.vy = (burst ? 0.6 + RANDOM.nextDouble() * 0.8 : (RANDOM.nextDouble() - 0.5) * 0.2) * spd;
        a.life = (float) (s.lifetime.get() * (burst ? 0.6 : 1.0) * (0.7 + RANDOM.nextDouble() * 0.6));
        a.size = (float) (0.07 * s.size.get() / 100.0 * (0.7 + RANDOM.nextDouble() * 0.6) * scale);
        a.rot = (float) (RANDOM.nextDouble() * Math.PI * 2);
        a.spin = (float) ((RANDOM.nextDouble() - 0.5) * 2.4 * s.spin.get() / 100.0);
        a.hue = RANDOM.nextFloat();
        STARS.add(a);
    }
}
