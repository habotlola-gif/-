package com.visualmod.multipoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Замкнутый путь точки прицеливания по хитбоксу цели (вид спереди).
 * Координаты: x от -1 (левый край) до 1 (правый), y от 0 (ноги) до 1 (макушка).
 * Хранится в config/visualmod-multipoint.json.
 */
public final class MultipointPath {
    public static final float MIN_Y = 0.02f, MAX_Y = 0.98f;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("visualmod-multipoint.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<float[]> points = new ArrayList<>();
    private static int version;
    // кэш сглаженного пути
    private static List<float[]> cache;
    private static int cacheVersion = -1, cacheArc = -1;
    private static float cacheScale = -1;
    private static float[] cacheLen;
    private static float cacheTotal;

    static {
        load();
    }

    private MultipointPath() {
    }

    public static List<float[]> points() {
        return points;
    }

    public static void touch() {
        version++;
    }

    public static void reset() {
        points.clear();
        // овал от груди до головы (как раньше был встроенный круг)
        int n = 8;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            points.add(new float[]{(float) (Math.cos(a) * 0.70), (float) (0.62 + Math.sin(a) * 0.26)});
        }
        touch();
    }

    public static float clampX(float x) {
        return Math.max(-1f, Math.min(1f, x));
    }

    public static float clampY(float y) {
        return Math.max(MIN_Y, Math.min(MAX_Y, y));
    }

    public static void load() {
        points.clear();
        try {
            if (Files.exists(FILE)) {
                List<List<Float>> raw = GSON.fromJson(Files.readString(FILE), new TypeToken<List<List<Float>>>() {}.getType());
                if (raw != null) {
                    for (List<Float> p : raw) {
                        if (p != null && p.size() >= 2) points.add(new float[]{clampX(p.get(0)), clampY(p.get(1))});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (points.size() < 3) reset();
        touch();
    }

    public static void save() {
        try {
            List<List<Float>> raw = new ArrayList<>();
            for (float[] p : points) raw.add(List.of(p[0], p[1]));
            Files.writeString(FILE, GSON.toJson(raw));
        } catch (IOException ignored) {
        }
    }

    /** Сглаженный (Chaikin) и промасштабированный путь для отрисовки и выборки. */
    public static List<float[]> smoothed(int arc, float scale) {
        if (cache != null && cacheVersion == version && cacheArc == arc && cacheScale == scale) return cache;
        float cx = 0, cy = 0;
        for (float[] p : points) { cx += p[0]; cy += p[1]; }
        cx /= points.size(); cy /= points.size();
        List<float[]> cur = new ArrayList<>();
        for (float[] p : points) {
            cur.add(new float[]{clampX(cx + (p[0] - cx) * scale), clampY(cy + (p[1] - cy) * scale)});
        }
        for (int it = 0; it < arc; it++) {
            List<float[]> next = new ArrayList<>();
            for (int i = 0; i < cur.size(); i++) {
                float[] a = cur.get(i), b = cur.get((i + 1) % cur.size());
                next.add(new float[]{0.75f * a[0] + 0.25f * b[0], 0.75f * a[1] + 0.25f * b[1]});
                next.add(new float[]{0.25f * a[0] + 0.75f * b[0], 0.25f * a[1] + 0.75f * b[1]});
            }
            cur = next;
        }
        cacheLen = new float[cur.size()];
        cacheTotal = 0;
        for (int i = 0; i < cur.size(); i++) {
            float[] a = cur.get(i), b = cur.get((i + 1) % cur.size());
            cacheLen[i] = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            cacheTotal += cacheLen[i];
        }
        cache = cur;
        cacheVersion = version; cacheArc = arc; cacheScale = scale;
        return cur;
    }

    /** Точка пути при фазе 0..1 (доля полного оборота по длине линии). */
    public static float[] sample(double phase, int arc, float scale) {
        List<float[]> path = smoothed(arc, scale);
        double target = (phase - Math.floor(phase)) * cacheTotal;
        for (int i = 0; i < path.size(); i++) {
            if (target <= cacheLen[i] || i == path.size() - 1) {
                float[] a = path.get(i), b = path.get((i + 1) % path.size());
                float t = cacheLen[i] <= 1e-6f ? 0 : (float) Math.min(1.0, target / cacheLen[i]);
                return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
            target -= cacheLen[i];
        }
        return path.get(0);
    }
}
