package com.visualmod.render;

import com.visualmod.gui.UiRenderer;
import com.visualmod.module.TargetEsp;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Режим «Молния» для TargetESP: три дуги вокруг цели, каждая вспыхивает по своей фазе и ломается
 * в зигзаг (рекурсивное деление отрезков со смещением середины шумом + боковые ответвления).
 *
 * Алгоритм сделан по описанию эффекта и переписан под нашу базу: геометрия строится в мировых
 * координатах, точки проецируются через {@link WorldProjection} и рисуются той же светящейся линией,
 * что и кольцо/стрелки. Отдельные render-pipeline и шейдеры не нужны.
 */
public final class LightningEsp {
    private static final int BOLTS = 3;
    private static final int DEPTH = 4;
    private static final float CELL_RATE = 6.0f;     // как часто «переезжает» зигзаг (раз в секунду)
    private static float visibility = 1.0f;          // плавное затухание за стенами
    private static long lastNanos;

    private LightningEsp() {
    }

    public static void render(UiRenderer ui, TargetEsp s, LivingEntity entity, Vec3 base, double time,
                              int color, float partialTick, float baseAlpha) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.016f : Math.min((now - lastNanos) / 1.0e9f, 0.05f);
        lastNanos = now;
        boolean visible = mc.player != null && mc.player.hasLineOfSight(entity);
        visibility += ((visible ? 1.0f : 0.4f) - visibility) * (1.0f - (float) Math.exp(-dt * 8.0f));

        // вспышка красным при ударе по цели
        float hurt = Math.max(0.0f, entity.hurtTime - partialTick);
        float flash = (float) Math.sin(hurt * Math.PI / 10.0) * 0.5f;
        int tint = UiRenderer.lerpColor(color, 0xFFFF2020, Mth.clamp(flash, 0.0f, 1.0f));

        float sizeMul = s.size.getFloat() / 100.0f;
        double radius = (entity.getBbWidth() * 0.75 + 0.16) * sizeMul;
        double height = entity.getBbHeight();
        float thickness = Math.max(2.0f, 1.8f * ui.guiScale() * s.thickness.getFloat() / 100.0f);
        float glow = s.glow.getFloat() / 100.0f;

        int cell = (int) Math.floor(time * CELL_RATE);
        float cellFrac = (float) (time * CELL_RATE - cell);

        for (int bolt = 0; bolt < BOLTS; bolt++) {
            double phase = time * 1.3 + (double) bolt / BOLTS;
            int flashCell = (int) Math.floor(phase);
            float flashFrac = (float) (phase - flashCell);
            // вспышка: быстро загорается, потом гаснет, у каждой дуги своя фаза
            float fadeIn = Mth.clamp(flashFrac / 0.08f, 0.0f, 1.0f);
            float fadeOut = 1.0f - Mth.clamp((flashFrac - 0.42f) / 0.3f, 0.0f, 1.0f);
            float intensity = fadeIn * fadeOut * Mth.clamp(0.55f + 0.45f * noise(bolt, 900, cell * 4), 0.0f, 1.0f);
            if (intensity <= 0.02f) continue;

            double bob = height * 0.16 * Math.sin(time * 1.7 + bolt * 2.1);
            double baseAngle = noise(bolt, 11, flashCell) * Math.PI;
            double arc = 0.6 + 0.5 * Math.abs(noise(bolt, 12, flashCell));
            double arcDir = noise(bolt, 13, flashCell) >= 0.0f ? 1.0 : -1.0;
            double startY = height * (0.85 + 0.05 * noise(bolt, 14, flashCell));
            double drop = height * 0.67;
            double boltRadius = radius * (1.0 + 0.12 * noise(bolt, 15, flashCell));
            double scroll = time * 0.9;

            // 4 опорные точки по спирали вокруг тела
            Vec3[] ctrl = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                double t = i / 3.0;
                double ang = baseAngle + scroll + arcDir * arc * Math.PI * 2.0 * t;
                ctrl[i] = base.add(Math.cos(ang) * boltRadius, startY - drop * t + bob, Math.sin(ang) * boltRadius);
            }

            List<Vec3> main = new ArrayList<>();
            List<List<Vec3>> branches = new ArrayList<>();
            main.add(ctrl[0]);
            for (int i = 0; i < 3; i++) {
                int seed = ((bolt * 131 + i + 1) * 17 + flashCell * 101) | 1;
                split(main, branches, ctrl[i], ctrl[i + 1], DEPTH, seed, 0, cell, cellFrac);
            }

            ui.setOpacity(Mth.clamp(baseAlpha * intensity * visibility, 0.0f, 1.0f));
            float th = thickness * (0.7f + 0.5f * intensity);
            TargetEspRenderer.drawLuminousLine(ui, project(main, false), th, tint, glow);
            for (List<Vec3> br : branches) {
                TargetEspRenderer.drawLuminousLine(ui, project(br, true), th * 0.55f, tint, glow * 0.8f);
            }
        }
        ui.setOpacity(baseAlpha);
    }

    /** Мировые точки -> экранные {x, y, интенсивность}. Ветки к концу гаснут. */
    private static float[][] project(List<Vec3> pts, boolean taper) {
        float[][] out = new float[pts.size()][];
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            float[] p = WorldProjection.toScreen(pts.get(i));
            if (p == null) continue;
            float k = taper ? 1.0f - (float) i / Math.max(1, n - 1) : 1.0f;
            out[i] = new float[]{p[0], p[1], 0.35f + 0.65f * k};
        }
        return out;
    }

    /**
     * Делит отрезок a-b пополам, смещая середину поперёк отрезка по шуму, и рекурсивно повторяет.
     * Добавляет в out все точки после a (включая b). На подходящих делениях выпускает боковую ветку.
     */
    private static void split(List<Vec3> out, List<List<Vec3>> branches, Vec3 a, Vec3 b, int depth,
                              int seed, int level, int cell, float frac) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (depth <= 0 || len < 1.0e-4) {
            out.add(b);
            return;
        }
        Vec3 dir = d.scale(1.0 / len);
        // два вектора, перпендикулярных отрезку (для вертикальных берём другую опорную ось)
        Vec3 helper = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 p1 = dir.cross(helper).normalize();
        Vec3 p2 = dir.cross(p1);

        float h1 = smoothNoise(seed, 0, cell, frac);
        float h2 = smoothNoise(seed, 1, cell, frac);
        Vec3 mid = a.add(b).scale(0.5).add(p1.scale(h1 * len * 0.3)).add(p2.scale(h2 * len * 0.3));

        split(out, branches, a, mid, depth - 1, seed * 2 + 7, level, cell, frac);

        if (level < 2 && depth >= 2 && smoothNoise(seed, 2, cell, frac) > 0.45f) {
            float b1 = smoothNoise(seed, 3, cell, frac);
            float b2 = smoothNoise(seed, 4, cell, frac);
            Vec3 bd = dir.scale(0.4).add(p1.scale(b1)).add(p2.scale(b2));
            double bl = bd.length();
            if (bl > 1.0e-5) {
                Vec3 end = mid.add(bd.scale(len * 0.75 / bl));
                List<Vec3> branch = new ArrayList<>();
                branch.add(mid);
                split(branch, branches, mid, end, Math.min(depth - 1, 3), seed * 3 + 13, level + 1, cell, frac);
                branches.add(branch);
            }
        }

        split(out, branches, mid, b, depth - 1, seed * 2 + 9, level, cell, frac);
    }

    /** Детерминированный шум в диапазоне [-1, 1]. */
    static float noise(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 32768.0f - 1.0f;
    }

    /** Плавный переход между соседними ячейками шума, чтобы зигзаг «ползёт», а не прыгает. */
    static float smoothNoise(int a, int b, int cell, float t) {
        float from = noise(a, b, cell * 4);
        float to = noise(a, b, (cell + 1) * 4);
        float s = t * t * (3.0f - 2.0f * t);
        return from + (to - from) * s;
    }
}
