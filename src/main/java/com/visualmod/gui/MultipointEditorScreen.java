package com.visualmod.gui;

import com.mojang.blaze3d.platform.Window;
import com.visualmod.module.ModuleManager;
import com.visualmod.multipoint.MultipointPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Редактор пути Multipoint: манекен (вид спереди, рамка = хитбокс), на нём замкнутый путь из точек.
 * ЛКМ по точке: двигать точку. ЛКМ по линии: двигать линию целиком (обе её точки).
 * ПКМ по линии: добавить точку. ПКМ по точке: удалить (минимум 3).
 * Белая точка показывает, как прицел будет бегать по пути с текущими настройками.
 */
public class MultipointEditorScreen extends Screen {
    private final Screen parent;
    private final long openedAt = System.nanoTime();

    private int dragPoint = -1;           // индекс двигаемой точки
    private int dragSegment = -1;         // индекс двигаемой линии (от точки i к i+1)
    private float segGrabX, segGrabY;     // где схватили линию (в нормализованных координатах)
    private float[] segStartA, segStartB;

    // раскладка (в пикселях окна), обновляется при каждом кадре
    private int rx, ry, rw, rh;
    private int btnResetX, btnDoneX, btnY, btnW, btnH;
    private float dpScale = 1.0f;

    private double dp(float v) { return v * this.dpScale; }

    public MultipointEditorScreen(Screen parent) {
        super(Component.literal("Multipoint"));
        this.parent = parent;
    }

    // ---- координаты ----

    private float toPx(float nx) { return this.rx + (nx + 1f) / 2f * this.rw; }
    private float toPy(float ny) { return this.ry + (1f - ny) * this.rh; }
    private float toNx(double px) { return (float) ((px - this.rx) / this.rw * 2.0 - 1.0); }
    private float toNy(double py) { return (float) (1.0 - (py - this.ry) / this.rh); }

    private double mousePx() {
        Window w = this.minecraft.getWindow();
        return this.minecraft.mouseHandler.xpos() * (double) w.getWidth() / (double) Math.max(1, w.getScreenWidth());
    }

    private double mousePy() {
        Window w = this.minecraft.getWindow();
        return this.minecraft.mouseHandler.ypos() * (double) w.getHeight() / (double) Math.max(1, w.getScreenHeight());
    }

    // ---- отрисовка ----

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        UiRenderer ui = UiRenderer.begin(graphics);
        try {
            draw(ui, (int) mousePx(), (int) mousePy());
        } finally {
            ui.end();
        }
    }

    private void layout(UiRenderer ui) {
        this.dpScale = ui.dp(100f) / 100f;
        int sw = this.width * ui.guiScale(), sh = this.height * ui.guiScale();
        int pw = Math.min(ui.dp(440f), sw - ui.dp(24f));
        int ph = Math.min(ui.dp(640f), sh - ui.dp(24f));
        int px = (sw - pw) / 2, py = (sh - ph) / 2;
        int top = py + ui.dp(78f), bottom = py + ph - ui.dp(62f);
        this.rh = Math.max(ui.dp(160f), bottom - top);
        this.rw = this.rh / 3;   // хитбокс игрока 0.6 x 1.8
        this.rx = px + (pw - this.rw) / 2;
        this.ry = top;
        this.btnW = ui.dp(120f);
        this.btnH = ui.dp(32f);
        this.btnY = py + ph - ui.dp(48f);
        this.btnResetX = px + pw / 2 - this.btnW - ui.dp(8f);
        this.btnDoneX = px + pw / 2 + ui.dp(8f);
        // панель
        ui.shadow(px, py, pw, ph, ui.dp(14f), ui.dp(26f), 0x99000000);
        ui.roundRectBordered(px, py, pw, ph, ui.dp(14f), 1, 0xFF2B2B33, 0xF2101014);
        ui.textCentered(UiRenderer.TextStyle.TITLE, "Расположение точек", px, py + ui.dp(8f), ui.dp(30f), -1);
        ui.textCentered(UiRenderer.TextStyle.SMALL, "ЛКМ: двигать точку или линию", px, py + ui.dp(36f), ui.dp(14f), -7566184);
        ui.textCentered(UiRenderer.TextStyle.SMALL, "ПКМ: на линии — добавить точку, на точке — удалить", px, py + ui.dp(52f), ui.dp(14f), -7566184);
    }

    private void draw(UiRenderer ui, int mx, int my) {
        layout(ui);
        // рамка хитбокса
        ui.roundRectBordered(this.rx, this.ry, this.rw, this.rh, ui.dp(8f), 2, 0xFF4A4A58, 0xFF17171D);

        drawMannequin(ui);

        List<float[]> pts = MultipointPath.points();
        int n = pts.size();

        // сглаженный предпросмотр (тонкая линия) с текущими настройками дуги и размера
        var ex = ModuleManager.EXPERIMENTAL;
        List<float[]> smooth = MultipointPath.smoothed(ex.mpArcValue(), ex.mpSizeValue());
        for (int i = 0; i < smooth.size(); i++) {
            float[] a = smooth.get(i), b = smooth.get((i + 1) % smooth.size());
            ui.capsule(toPx(a[0]), toPy(a[1]), toPx(b[0]), toPy(b[1]), ui.dp(2f), 1, 0x66FFFFFF);
        }

        // линии контура (то, что двигается мышью)
        int hoverSeg = this.dragSegment >= 0 ? this.dragSegment : (hitPoint(mx, my) >= 0 ? -1 : hitSegment(mx, my));
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i), b = pts.get((i + 1) % n);
            int col = i == hoverSeg ? 0xFFFF6B6B : 0xFFE03A3A;
            ui.capsule(toPx(a[0]), toPy(a[1]), toPx(b[0]), toPy(b[1]), ui.dp(i == hoverSeg ? 4f : 3f), 2, col);
        }
        // точки
        int hoverPt = this.dragPoint >= 0 ? this.dragPoint : hitPoint(mx, my);
        for (int i = 0; i < n; i++) {
            float[] p = pts.get(i);
            int size = ui.dp(i == hoverPt ? 14f : 11f);
            int x = Math.round(toPx(p[0])) - size / 2, y = Math.round(toPy(p[1])) - size / 2;
            ui.blurCircle(x, y, size, 2, i == hoverPt ? 0xFFFFFFFF : 0xFFFFD0D0);
            ui.blurCircle(x + ui.dp(3f), y + ui.dp(3f), size - ui.dp(6f), 1, 0xFFE03A3A);
        }

        // бегущая точка прицела
        double t = (System.nanoTime() - this.openedAt) / 1.0e9 * ex.mpSpeedValue();
        double f = t - Math.floor(t);
        double phase = switch (ex.mpDirectionIndex()) { case 1 -> 1 - f; case 2 -> f < 0.5 ? f * 2 : 2 - f * 2; default -> f; };
        float[] s = MultipointPath.sample(phase, ex.mpArcValue(), ex.mpSizeValue());
        int dot = ui.dp(10f);
        ui.blurCircle(Math.round(toPx(s[0])) - dot, Math.round(toPy(s[1])) - dot, dot * 2, 8, 0x88FFFFFF);
        ui.blurCircle(Math.round(toPx(s[0])) - dot / 2, Math.round(toPy(s[1])) - dot / 2, dot, 1, 0xFFFFFFFF);

        // кнопки
        button(ui, this.btnResetX, "Сбросить", mx, my);
        button(ui, this.btnDoneX, "Готово", mx, my);
    }

    private void button(UiRenderer ui, int x, String label, int mx, int my) {
        boolean hov = mx >= x && mx < x + this.btnW && my >= this.btnY && my < this.btnY + this.btnH;
        ui.roundRect(x, this.btnY, this.btnW, this.btnH, ui.dp(8f), hov ? 0xFF3A3A48 : 0xFF26262F);
        int tw = ui.textWidth(UiRenderer.TextStyle.BODY, label);
        ui.textCentered(UiRenderer.TextStyle.BODY, label, x + (this.btnW - tw) / 2, this.btnY, this.btnH, -1);
    }

    /** Скин игрока (твой, иначе Стив) спереди в реальных пропорциях: рамка = хитбокс 0.6 x 1.8. */
    private void drawMannequin(UiRenderer ui) {
        // Основной вариант: настоящая 3D-модель игрока (тот же рендер, что в инвентаре), анфас.
        if (this.minecraft.player != null) {
            int gs = ui.guiScale();
            int x1 = this.rx / gs, y1 = this.ry / gs;
            int x2 = (this.rx + this.rw) / gs, y2 = (this.ry + this.rh) / gs;
            int scale = Math.max(8, Math.round((this.rh / (float) gs) / 1.8f));
            float cx = (x1 + x2) / 2.0f, cy = (y1 + y2) / 2.0f;     // «мышь» в центре = смотрит прямо
            InventoryScreen.renderEntityInInventoryFollowsMouse(ui.graphics(), x1, y1, x2, y2, scale, 0.0f, cx, cy, this.minecraft.player);
            return;
        }
        drawSkinFallback(ui);
    }

    /** Запасной вариант без игрока в мире: плоский скин Стива. */
    private void drawSkinFallback(UiRenderer ui) {
        PlayerSkin skin = DefaultPlayerSkin.getDefaultSkin();
        Identifier tex = skin.body().texturePath();
        boolean slim = skin.model() == PlayerModelType.SLIM;
        int armW = slim ? 3 : 4;
        float u = this.rh / 32f;          // пикселей экрана на один пиксель скина
        float cx = this.rx + this.rw / 2f;
        float top = this.ry;
        GuiGraphics g = ui.graphics();

        // база
        skinPart(g, tex, cx - 4 * u, top, 8 * u, 8 * u, 8, 8, 8, 8);                        // голова
        skinPart(g, tex, cx - 4 * u, top + 8 * u, 8 * u, 12 * u, 20, 20, 8, 12);            // торс
        skinPart(g, tex, cx - 4 * u - armW * u, top + 8 * u, armW * u, 12 * u, 44, 20, armW, 12); // правая рука (слева на экране)
        skinPart(g, tex, cx + 4 * u, top + 8 * u, armW * u, 12 * u, 36, 52, armW, 12);      // левая рука
        skinPart(g, tex, cx - 4 * u, top + 20 * u, 4 * u, 12 * u, 4, 20, 4, 12);           // правая нога
        skinPart(g, tex, cx, top + 20 * u, 4 * u, 12 * u, 20, 52, 4, 12);                   // левая нога
        // второй слой (шляпа, куртка, рукава, штаны) чуть крупнее
        float e = 0.5f * u;
        skinPart(g, tex, cx - 4 * u - e, top - e, 8 * u + 2 * e, 8 * u + 2 * e, 40, 8, 8, 8);
        skinPart(g, tex, cx - 4 * u - e / 2, top + 8 * u - e / 2, 8 * u + e, 12 * u + e, 20, 36, 8, 12);
        skinPart(g, tex, cx - 4 * u - armW * u - e / 2, top + 8 * u - e / 2, armW * u + e, 12 * u + e, 44, 36, armW, 12);
        skinPart(g, tex, cx + 4 * u - e / 2, top + 8 * u - e / 2, armW * u + e, 12 * u + e, 52, 52, armW, 12);
        skinPart(g, tex, cx - 4 * u - e / 2, top + 20 * u - e / 2, 4 * u + e, 12 * u + e, 4, 36, 4, 12);
        skinPart(g, tex, cx - e / 2, top + 20 * u - e / 2, 4 * u + e, 12 * u + e, 4, 52, 4, 12);
    }

    private void skinPart(GuiGraphics g, Identifier tex, float x, float y, float w, float h, int sx, int sy, int sw, int sh) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, Math.round(x), Math.round(y), (float) sx, (float) sy,
                Math.round(w), Math.round(h), sw, sh, 64, 64);
    }

    // ---- попадания ----

    private int hitPoint(double mx, double my) {
        List<float[]> pts = MultipointPath.points();
        double r = dp(11f);
        for (int i = pts.size() - 1; i >= 0; i--) {
            double dx = toPx(pts.get(i)[0]) - mx, dy = toPy(pts.get(i)[1]) - my;
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    private int hitSegment(double mx, double my) {
        List<float[]> pts = MultipointPath.points();
        int n = pts.size();
        double best = dp(9f);
        int found = -1;
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i), b = pts.get((i + 1) % n);
            double d = distToSegment(mx, my, toPx(a[0]), toPy(a[1]), toPx(b[0]), toPy(b[1]));
            if (d <= best) { best = d; found = i; }
        }
        return found;
    }

    private static double distToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / l2));
        return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    // ---- ввод ----

    private boolean inButton(double mx, double my, int x) {
        return mx >= x && mx < x + this.btnW && my >= this.btnY && my < this.btnY + this.btnH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = mousePx(), my = mousePy();
        List<float[]> pts = MultipointPath.points();
        if (event.button() == 0) {
            if (inButton(mx, my, this.btnResetX)) { MultipointPath.reset(); MultipointPath.save(); return true; }
            if (inButton(mx, my, this.btnDoneX)) { this.onClose(); return true; }
            int p = hitPoint(mx, my);
            if (p >= 0) { this.dragPoint = p; return true; }
            int s = hitSegment(mx, my);
            if (s >= 0) {
                this.dragSegment = s;
                this.segGrabX = toNx(mx);
                this.segGrabY = toNy(my);
                this.segStartA = pts.get(s).clone();
                this.segStartB = pts.get((s + 1) % pts.size()).clone();
                return true;
            }
        } else if (event.button() == 1) {
            int p = hitPoint(mx, my);
            if (p >= 0) {
                if (pts.size() > 3) { pts.remove(p); MultipointPath.touch(); MultipointPath.save(); }
                return true;
            }
            int s = hitSegment(mx, my);
            if (s >= 0) {
                pts.add(s + 1, new float[]{MultipointPath.clampX(toNx(mx)), MultipointPath.clampY(toNy(my))});
                MultipointPath.touch();
                MultipointPath.save();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = mousePx(), my = mousePy();
        List<float[]> pts = MultipointPath.points();
        if (this.dragPoint >= 0 && this.dragPoint < pts.size()) {
            float[] p = pts.get(this.dragPoint);
            p[0] = MultipointPath.clampX(toNx(mx));
            p[1] = MultipointPath.clampY(toNy(my));
            MultipointPath.touch();
            return true;
        }
        if (this.dragSegment >= 0 && this.dragSegment < pts.size()) {
            float dnx = toNx(mx) - this.segGrabX, dny = toNy(my) - this.segGrabY;
            // сдвигаем линию целиком, но не даём ей выйти за хитбокс
            float lo = Math.max(-1f - Math.min(this.segStartA[0], this.segStartB[0]), -1f);
            dnx = Math.max(-1f - Math.min(this.segStartA[0], this.segStartB[0]),
                    Math.min(1f - Math.max(this.segStartA[0], this.segStartB[0]), dnx));
            dny = Math.max(MultipointPath.MIN_Y - Math.min(this.segStartA[1], this.segStartB[1]),
                    Math.min(MultipointPath.MAX_Y - Math.max(this.segStartA[1], this.segStartB[1]), dny));
            float[] a = pts.get(this.dragSegment), b = pts.get((this.dragSegment + 1) % pts.size());
            a[0] = this.segStartA[0] + dnx; a[1] = this.segStartA[1] + dny;
            b[0] = this.segStartB[0] + dnx; b[1] = this.segStartB[1] + dny;
            MultipointPath.touch();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.dragPoint >= 0 || this.dragSegment >= 0) {
            this.dragPoint = -1;
            this.dragSegment = -1;
            MultipointPath.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        MultipointPath.save();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
