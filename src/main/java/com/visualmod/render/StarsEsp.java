package com.visualmod.render;

import com.visualmod.gui.UiRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Режим «Звёзды» для TargetESP: мягкие заливные звёзды (форма с референса) разбросаны вокруг цели от ног до головы,
 * медленно кружатся и мерцают. При ударе разлетаются от тела, вспыхивают, увеличиваются и закручиваются,
 * потом плавно возвращаются. Количество фиксированное, настраивается только цвет.
 */
public final class StarsEsp {
    private static final int COUNT = 16;
    private static final int TEX = 256;
    private static final Identifier STAR = Identifier.fromNamespaceAndPath("visualmod", "textures/gui/star_filled.png");

    private static int entityId = -1;
    private static List<StarDef> stars = new ArrayList<>();
    private static float hit;            // 1 сразу после удара, затухает к 0
    private static int lastHurtTime;
    private static long lastNanos;

    private StarsEsp() {
    }

    private static final class StarDef {
        float angle, height, radius, size, spinDir, spinSpeed, phase, rotSpeed, rot0;
    }

    /** Удар по цели (вызывается из TargetTracker.onAttack). */
    public static void onAttack(LivingEntity target) {
        if (target != null && target.getId() == entityId) {
            hit = 1.0f;
        }
    }

    private static void rebuild(LivingEntity e) {
        entityId = e.getId();
        stars = new ArrayList<>();
        Random r = new Random(e.getId() * 7919L + 13L);
        float reach = e.getBbWidth() * 0.5f + 0.32f;
        for (int i = 0; i < COUNT; i++) {
            StarDef s = new StarDef();
            // высота: равномерно от ног до головы, с небольшим дрожанием, чтобы не выстраивались в линию
            s.height = Mth.clamp((i + r.nextFloat() * 0.9f) / COUNT, 0.0f, 1.0f);
            s.angle = r.nextFloat() * (float) (Math.PI * 2);
            s.radius = reach * (0.75f + r.nextFloat() * 0.75f);
            s.size = 0.075f + r.nextFloat() * 0.06f;
            s.spinDir = r.nextBoolean() ? 1.0f : -1.0f;
            s.spinSpeed = 0.25f + r.nextFloat() * 0.5f;
            s.phase = r.nextFloat() * (float) (Math.PI * 2);
            s.rotSpeed = (r.nextFloat() - 0.5f) * 2.0f;
            s.rot0 = r.nextFloat() * (float) (Math.PI * 2);
            stars.add(s);
        }
    }

    public static void render(UiRenderer ui, LivingEntity entity, Vec3 base, double time, Vec3 camera,
                              double pxPerUnit, int color, float alpha, float partialTick) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min((now - lastNanos) / 1.0e9f, 0.05f);
        lastNanos = now;

        if (entity.getId() != entityId) {
            rebuild(entity);
            hit = 0.0f;
            lastHurtTime = 0;
        }
        // удар по цели от кого угодно: hurtTime резко вырастает
        if (entity.hurtTime > lastHurtTime) hit = 1.0f;
        lastHurtTime = entity.hurtTime;
        hit = Math.max(0.0f, hit - dt * 1.6f);            // ~0.6 с на затухание

        float burst = hit * hit * (3.0f - 2.0f * hit);     // плавная кривая
        double height = entity.getBbHeight();
        GuiGraphics g = ui.graphics();

        record Item(float x, float y, float r, float rot, float a, double dist) {}
        List<Item> items = new ArrayList<>();
        for (StarDef s : stars) {
            double ang = s.angle + s.spinDir * s.spinSpeed * time + burst * s.spinDir * 0.45;
            double rad = s.radius * (1.0 + 0.22 * burst) + 0.04 * Math.sin(time * 1.3 + s.phase);
            double y = height * s.height + 0.05 * Math.sin(time * 1.7 + s.phase) + burst * 0.06 * (s.height - 0.5);
            Vec3 p = base.add(Math.cos(ang) * rad, y, Math.sin(ang) * rad);
            float[] scr = WorldProjection.toScreen(p);
            if (scr == null) continue;
            double dist = Math.max(0.3, p.distanceTo(camera));
            float size = s.size * (1.0f + 0.3f * burst);
            float r = (float) (size * pxPerUnit / dist);
            if (r < 2.5f) continue;
            float twinkle = 0.6f + 0.4f * (float) Math.sin(time * 3.0 + s.phase);
            float a = Mth.clamp(alpha * Math.min(1.0f, twinkle + burst * 0.6f), 0.0f, 1.0f);
            float rot = (float) (s.rot0 + s.rotSpeed * time + burst * s.rotSpeed * 1.5);
            items.add(new Item(scr[0], scr[1], r, rot, a, dist));
        }
        items.sort(Comparator.comparingDouble((Item i) -> -i.dist()));

        int flash = UiRenderer.lerpColor(color, -1, 0.65f * burst);   // при ударе звёзды белеют
        for (Item it : items) {
            int size = Math.max(6, Math.round(it.r() * 2.0f));
            int big = Math.round(size * (1.5f + 0.35f * burst));
            g.pose().pushMatrix();
            g.pose().translate(it.x(), it.y());
            g.pose().rotate(it.rot());
            g.blit(RenderPipelines.GUI_TEXTURED, STAR, -big / 2, -big / 2, 0.0f, 0.0f, big, big, TEX, TEX, TEX, TEX,
                    argb(color, Math.min(0.40f, 0.14f + 0.26f * burst) * it.a()));       // свечение
            g.blit(RenderPipelines.GUI_TEXTURED, STAR, -size / 2, -size / 2, 0.0f, 0.0f, size, size, TEX, TEX, TEX, TEX,
                    argb(flash, 0.98f * it.a()));
            g.pose().popMatrix();
        }
    }

    private static int argb(int color, float alpha) {
        return Math.min(255, Math.max(0, Math.round(alpha * 255.0f))) << 24 | color & 0xFFFFFF;
    }
}
