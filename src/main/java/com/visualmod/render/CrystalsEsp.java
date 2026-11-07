package com.visualmod.render;

import com.visualmod.VisualModClient;
import com.visualmod.gui.UiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Режим «Кристаллы» для TargetESP: 15 объёмных ромбов-самоцветов (три ровных яруса по пять) (стоят вертикально, повёрнуты по углу своей орбиты) парят вокруг цели на разной высоте
 * и расстоянии и обходят её по кругу. У каждого мягкое свечение и грани разной яркости.
 *
 * Расстановка и движение взяты из присланного кода (семена по индексу, орбита по времени, радиус/высота),
 * геометрия кристалла восстановлена по скриншоту. Рисуется в HUD-слое: вершины проецируются через
 * {@link WorldProjection}, грани заливаются треугольниками, дальние кристаллы рисуются первыми.
 */
public final class CrystalsEsp {
    private static final int TIERS = 3;               // ярусы: ноги, грудь, голова
    private static final int PER_TIER = 5;            // кристаллов в ярусе
    private static final int COUNT = TIERS * PER_TIER;
    private static final float[] TIER_HEIGHT = {0.14f, 0.52f, 0.90f};

    private static long lastLog;

    private CrystalsEsp() {
    }

    private record Face(Vec3 a, Vec3 b, Vec3 c, double depth, float shade) {}
    private record Crystal(double dist, float cx, float cy, float glowR, List<Face> faces) {}

    public static void render(UiRenderer ui, LivingEntity entity, Vec3 base, float speed, float sizeMul, float glow,
                              Vec3 camera, double pxPerUnit, int color, float alpha, float partialTick, boolean visible) {
        try {
            int[] stat = renderInner(ui, entity, base, speed, sizeMul, glow, camera, pxPerUnit, color, alpha, partialTick, visible);
            long now = System.currentTimeMillis();
            if (now - lastLog > 3000L) {
                lastLog = now;
                VisualModClient.LOGGER.info("CrystalsEsp: кристаллов {}, граней {}, alpha {}, size {}, color {}", stat[0], stat[1], alpha, sizeMul, Integer.toHexString(color));
            }
        } catch (Throwable t) {
            VisualModClient.LOGGER.error("CrystalsEsp: ошибка отрисовки", t);
        }
    }

    private static int[] renderInner(UiRenderer ui, LivingEntity entity, Vec3 base, float speed, float sizeMul, float glow,
                              Vec3 camera, double pxPerUnit, int color, float alpha, float partialTick, boolean visible) {
        Minecraft mc = Minecraft.getInstance();
        float eased = easeOutCubic(alpha);
        float time = (mc.player.tickCount + partialTick) * 6.0f * speed;     // градусы
        float height = entity.getBbHeight();
        float halfWidth = entity.getBbWidth() * 0.5f;
        float vis = visible ? 1.0f : 0.4f;                                 // за стеной приглушаем

        Vec3 light = new Vec3(0.35, 0.85, 0.40).normalize();
        List<Crystal> crystals = new ArrayList<>();

        for (int i = 0; i < COUNT; i++) {
            // Ровные ярусы: в каждом PER_TIER камней через равные углы, соседние ярусы сдвинуты на полшага.
            int tier = i / PER_TIER, slot = i % PER_TIER;
            float angle = time + slot * (360.0f / PER_TIER) + tier * (180.0f / PER_TIER);
            float radius = halfWidth + 0.24f;
            double rad = Math.toRadians(angle);
            double bob = 0.03 * Math.sin(Math.toRadians(time * 1.2 + tier * 120.0));   // лёгкое парение всего яруса
            Vec3 c = base.add(radius * Math.cos(rad), TIER_HEIGHT[tier] * height + bob, radius * Math.sin(rad));

            float scale = 0.18f * eased * sizeMul;
            double half = scale * 1.35;        // половина высоты камня

            // Камень стоит вертикально и повёрнут по углу своей орбиты (как dirAngle в оригинале),
            // поэтому все кристаллы выглядят одинаково аккуратно и просто обходят цель по кругу.
            Vec3 axis = new Vec3(0.0, 1.0, 0.0);
            Vec3 radial = new Vec3(Math.cos(rad), 0.0, Math.sin(rad));
            Vec3 tang = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
            double wr = scale * 0.50;          // толщина по радиусу
            double wt = scale * 0.78;          // ширина вдоль орбиты (камень «смотрит» наружу широкой гранью)

            Vec3 top = c.add(axis.scale(half)), bot = c.subtract(axis.scale(half));
            Vec3[] eq = {c.add(radial.scale(wr)), c.add(tang.scale(wt)), c.subtract(radial.scale(wr)), c.subtract(tang.scale(wt))};

            List<Face> faces = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                Vec3 e0 = eq[k], e1 = eq[(k + 1) % 4];
                addFace(faces, top, e0, e1, c, camera, light);
                addFace(faces, bot, e1, e0, c, camera, light);
            }
            float[] sc = WorldProjection.toScreen(c);
            if (sc == null || faces.isEmpty()) continue;
            double dist = Math.max(0.3, c.distanceTo(camera));
            float glowR = (float) (scale * 3.6f * 0.5f * pxPerUnit / dist);
            crystals.add(new Crystal(dist, sc[0], sc[1], glowR, faces));
        }
        crystals.sort(Comparator.comparingDouble((Crystal cr) -> -cr.dist()));

        for (Crystal cr : crystals) {
            // мягкое свечение
            if (glow > 0.0f) {
                int d = Math.max(6, Math.round(cr.glowR() * 2.0f));
                ui.setOpacity(Mth.clamp(alpha * 0.30f * glow * vis, 0.0f, 1.0f));
                ui.blurCircle(Math.round(cr.cx()) - d / 2, Math.round(cr.cy()) - d / 2, d, d / 2, color);
            }
            // грани: дальние первыми
            ui.setOpacity(Mth.clamp(alpha * 0.85f * vis, 0.0f, 1.0f));
            cr.faces().sort(Comparator.comparingDouble((Face f) -> -f.depth()));
            for (Face f : cr.faces()) {
                float[] a = WorldProjection.toScreen(f.a()), b = WorldProjection.toScreen(f.b()), c = WorldProjection.toScreen(f.c());
                if (a == null || b == null || c == null) continue;
                // грани от тёмного оттенка цвета темы до светлого: видна объёмность камня
                int darkCol = UiRenderer.lerpColor(0xFF000000, 0xFF000000 | (color & 0xFFFFFF), 0.50f);
                int lightCol = UiRenderer.lerpColor(0xFF000000 | (color & 0xFFFFFF), 0xFFFFFFFF, 0.80f);
                float sh = (float) Math.pow(f.shade(), 1.4);
                int face = UiRenderer.lerpColor(darkCol, lightCol, sh);
                ui.triangle(a[0], a[1], b[0], b[1], c[0], c[1], 0xFF000000 | face & 0xFFFFFF);
                // чёткий светлый контур грани (рисуется проверенной «капсулой», видно даже если заливка не отрисовалась)
                int edge = 0xFF000000 | lightCol & 0xFFFFFF;
                float th = Math.max(1.0f, 1.1f * (float) ui.guiScale());
                ui.capsule(a[0], a[1], b[0], b[1], th, 0, edge);
                ui.capsule(b[0], b[1], c[0], c[1], th, 0, edge);
                ui.capsule(c[0], c[1], a[0], a[1], th, 0, edge);
            }
        }
        ui.setOpacity(alpha);
        int faceCount = 0;
        for (Crystal cr : crystals) faceCount += cr.faces().size();
        return new int[]{crystals.size(), faceCount};
    }

    private static void addFace(List<Face> out, Vec3 a, Vec3 b, Vec3 c, Vec3 center, Vec3 camera, Vec3 light) {
        Vec3 n = b.subtract(a).cross(c.subtract(a)).normalize();
        Vec3 mid = a.add(b).add(c).scale(1.0 / 3.0);
        if (n.dot(mid.subtract(center)) < 0.0) n = n.scale(-1.0);            // нормаль наружу
        if (n.dot(mid.subtract(camera)) >= 0.0) return;                      // задняя грань
        float shade = Mth.clamp((float) (0.15 + 0.85 * Math.abs(n.dot(light))), 0.0f, 1.0f);
        out.add(new Face(a, b, c, mid.distanceTo(camera), shade));
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
        return 1.0f - u * u * u;
    }
}
