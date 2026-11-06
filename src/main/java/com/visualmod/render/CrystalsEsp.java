package com.visualmod.render;

import com.visualmod.gui.UiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Режим «Кристаллы» для TargetESP: 14 объёмных ромбов-самоцветов (октаэдры) парят вокруг цели на разной высоте
 * и расстоянии и обходят её по кругу. У каждого мягкое свечение и грани разной яркости.
 *
 * Расстановка и движение взяты из присланного кода (семена по индексу, орбита по времени, радиус/высота),
 * геометрия кристалла восстановлена по скриншоту. Рисуется в HUD-слое: вершины проецируются через
 * {@link WorldProjection}, грани заливаются треугольниками, дальние кристаллы рисуются первыми.
 */
public final class CrystalsEsp {
    private static final int COUNT = 14;

    private CrystalsEsp() {
    }

    private record Face(Vec3 a, Vec3 b, Vec3 c, double depth, float shade) {}
    private record Crystal(double dist, float cx, float cy, float glowR, List<Face> faces) {}

    public static void render(UiRenderer ui, LivingEntity entity, Vec3 base, float speed, float sizeMul, float glow,
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
            float seed1 = (float) Math.sin(i * 1.7f + 0.3f) * 0.5f + 0.5f;
            float seed2 = (float) Math.cos(i * 2.3f + 0.7f) * 0.5f + 0.5f;
            float seed3 = (float) Math.sin(i * 3.1f + 1.1f) * 0.5f + 0.5f;

            float angle = time + i * (360.0f / COUNT) + seed1 * 12.0f;
            float radius = halfWidth + 0.25f + seed3 * 0.15f;
            double rad = Math.toRadians(angle);
            Vec3 c = base.add(radius * Math.cos(rad), seed2 * height, radius * Math.sin(rad));

            float scale = 0.18f * eased * sizeMul;
            double half = scale * 1.15;        // половина длины кристалла
            double wid = scale * 0.55;         // половина ширины

            // ось кристалла: по касательной орбиты, с наклоном (часть стоит почти вертикально, как на скриншоте)
            Vec3 tangent = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
            double tilt = (seed1 - 0.35) * Math.PI * 0.9;
            Vec3 axis = tangent.scale(Math.cos(tilt)).add(0.0, Math.sin(tilt), 0.0).normalize();
            Vec3 helper = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 p = axis.cross(helper).normalize();
            Vec3 q = axis.cross(p);
            double spin = Math.toRadians(time * 1.5 + i * 40.0);               // вращение вокруг оси: грани «играют»
            Vec3 pr = p.scale(Math.cos(spin)).add(q.scale(Math.sin(spin)));
            Vec3 qr = q.scale(Math.cos(spin)).subtract(p.scale(Math.sin(spin)));

            Vec3 top = c.add(axis.scale(half)), bot = c.subtract(axis.scale(half));
            Vec3[] eq = {c.add(pr.scale(wid)), c.add(qr.scale(wid)), c.subtract(pr.scale(wid)), c.subtract(qr.scale(wid))};

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
                // розово-белые грани: цвет темы, осветлённый по освещённости грани
                int face = UiRenderer.lerpColor(color, 0xFFFFFFFF, 0.25f + 0.65f * f.shade());
                face = UiRenderer.lerpColor(0xFF000000 | (color & 0xFFFFFF), face, 0.55f + 0.45f * f.shade());
                ui.triangle(a[0], a[1], b[0], b[1], c[0], c[1], 0xFF000000 | face & 0xFFFFFF);
            }
        }
        ui.setOpacity(alpha);
    }

    private static void addFace(List<Face> out, Vec3 a, Vec3 b, Vec3 c, Vec3 center, Vec3 camera, Vec3 light) {
        Vec3 n = b.subtract(a).cross(c.subtract(a)).normalize();
        Vec3 mid = a.add(b).add(c).scale(1.0 / 3.0);
        if (n.dot(mid.subtract(center)) < 0.0) n = n.scale(-1.0);            // нормаль наружу
        if (n.dot(mid.subtract(camera)) >= 0.0) return;                      // задняя грань
        float shade = Mth.clamp((float) (0.45 + 0.55 * Math.abs(n.dot(light))), 0.0f, 1.0f);
        out.add(new Face(a, b, c, mid.distanceTo(camera), shade));
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
        return 1.0f - u * u * u;
    }
}
