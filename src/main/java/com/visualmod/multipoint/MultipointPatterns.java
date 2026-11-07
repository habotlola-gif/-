package com.visualmod.multipoint;

import java.util.Random;

/**
 * Готовые паттерны мультипоинта. Точка идёт по замкнутой фигуре на хитбоксе цели (вид спереди):
 * x от -1 до 1 (слева направо), y от 0 до 1 (ноги -> макушка). Центр фигуры на уровне груди.
 *
 * Индексы паттернов: 1 круг, 2 восьмёрка, 3 зигзаг, 4 ромб. «Мой путь» (из редактора) считает MultipointPath.
 * «Случайный» каждый оборот рисует новую фигуру: либо кривую Лиссажу с случайными частотами, либо звезду.
 */
public final class MultipointPatterns {
    public static final int CENTER_Y = 62;     // 0.62 хитбокса
    private static final float CY = 0.62f, AX = 0.70f, AY = 0.26f;

    private MultipointPatterns() {
    }

    /** Точка готового паттерна 1..4 при фазе p в [0,1). Результат в «единичном» размере. */
    public static float[] preset(int id, float p) {
        double a = Math.PI * 2.0 * p;
        return switch (id) {
            case 1 -> new float[]{(float) Math.cos(a) * AX, CY + (float) Math.sin(a) * AY};
            case 2 -> new float[]{(float) Math.sin(a) * AX, CY + (float) Math.sin(2.0 * a) * AY};
            case 3 -> new float[]{tri(p * 3.0f) * AX, CY + (float) Math.sin(a) * AY};
            default -> new float[]{tri(p) * AX, CY + tri(p + 0.25f) * AY};     // ромб
        };
    }

    /** Фигура для «Случайного»: своя на каждый оборот (seed), замкнутая. */
    public static float[] random(long seed, float p) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 12345L);
        if (r.nextBoolean()) {
            int fa = 1 + r.nextInt(3), fb = 1 + r.nextInt(3);
            if (fa == fb) fb = fa % 3 + 1;
            double pa = r.nextDouble() * Math.PI * 2.0, pb = r.nextDouble() * Math.PI * 2.0;
            double a = Math.PI * 2.0 * p;
            return new float[]{(float) Math.sin(fa * a + pa) * AX, CY + (float) Math.sin(fb * a + pb) * AY};
        }
        int n = r.nextBoolean() ? 5 : 7;                 // звезда {n/2}
        float s = p * n;
        int j = (int) Math.floor(s);
        float t = s - j;
        float[] v0 = starVertex(j, n), v1 = starVertex(j + 1, n);
        return new float[]{v0[0] + (v1[0] - v0[0]) * t, v0[1] + (v1[1] - v0[1]) * t};
    }

    private static float[] starVertex(int j, int n) {
        double ang = Math.PI * 2.0 * ((j * 2) % n) / n + Math.PI / 2.0;
        return new float[]{(float) Math.cos(ang) * AX, CY + (float) Math.sin(ang) * AY};
    }

    /** Треугольная волна периода 1 в [-1, 1]. */
    private static float tri(float x) {
        float f = x - (float) Math.floor(x);
        return f < 0.5f ? 4.0f * f - 1.0f : 3.0f - 4.0f * f;
    }

    /**
     * Точка паттерна с учётом выбора.
     * @param mode 0 мой путь (не здесь), 1..4 готовые, 5 «По очереди», 6 «Случайный»
     * @param cycle номер оборота, frac фаза внутри оборота [0,1), every сколько оборотов держать один паттерн
     * @param scale размер пути (доля), seedBase отличает цели друг от друга
     * @return точка в координатах хитбокса или null, если нужен «Мой путь»
     */
    public static float[] sample(int mode, long cycle, float frac, int every, float scale, long seedBase, float[] mine) {
        float[] pt;
        if (mode >= 1 && mode <= 4) {
            pt = preset(mode, frac);
        } else if (mode == 5) {
            // по очереди: мой путь -> круг -> восьмёрка -> зигзаг -> ромб, смена между оборотами плавная
            long group = Math.floorDiv(cycle, Math.max(1, every));
            pt = blend(group, frac, mine, 0);
        } else {
            pt = blend(cycle, frac, mine, 1);
        }
        float x = MultipointPath.clampX(pt[0] * scale);
        float y = MultipointPath.clampY(CY + (pt[1] - CY) * scale);
        return new float[]{x, y};
    }

    /** Плавный переход между двумя соседними фигурами в первые 12% оборота. */
    private static float[] blend(long group, float frac, float[] mine, int kind) {
        float[] cur = figure(group, frac, mine, kind);
        float w = Math.min(1.0f, frac / 0.12f);
        w = w * w * (3.0f - 2.0f * w);
        if (w >= 1.0f) return cur;
        float[] prev = figure(group - 1, 1.0f - 1e-4f, mine, kind);       // где закончилась прошлая фигура
        return new float[]{prev[0] + (cur[0] - prev[0]) * w, prev[1] + (cur[1] - prev[1]) * w};
    }

    private static float[] figure(long group, float frac, float[] mine, int kind) {
        if (kind == 1) return random(group, frac);
        int id = (int) Math.floorMod(group, 5L);
        return id == 0 ? mine : preset(id, frac);
    }
}
