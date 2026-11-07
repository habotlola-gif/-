package com.visualmod.gui;

import com.mojang.blaze3d.platform.Window;
import com.visualmod.gui.RibbonRenderState;
import com.visualmod.gui.ShapeRenderState;
import com.visualmod.mixin.GuiGraphicsAccessor;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

public final class UiRenderer {
    private static final int FONT_BASELINE = 7;
    private static final float CAP_HEIGHT = 0.727f;
    private final GuiGraphics graphics;
    private final Font font;
    private final int guiScale;
    private final float density;
    private final String densitySuffix;
    private float opacity = 1.0f;

    private UiRenderer(GuiGraphics graphics, Font font, int guiScale, float density) {
        this.graphics = graphics;
        this.font = font;
        this.guiScale = guiScale;
        this.density = density;
        this.densitySuffix = "_" + Math.round(density * 10.0f);
    }

    public static UiRenderer begin(GuiGraphics graphics) {
        return UiRenderer.begin(graphics, 1.0f);
    }

    public static UiRenderer begin(GuiGraphics graphics, float scale) {
        Minecraft client = Minecraft.getInstance();
        Window window = client.getWindow();
        int guiScale = Math.max(1, window.getGuiScale());
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0f / (float)guiScale, 1.0f / (float)guiScale);
        return new UiRenderer(graphics, client.font, guiScale, UiRenderer.densityFor((float)window.getHeight() / 1080.0f * scale));
    }

    public void end() {
        this.graphics.pose().popMatrix();
    }

    public static float densityFor(float raw) {
        float stepped = (float)Math.round(raw * 2.0f) / 2.0f;
        return Math.max(1.0f, Math.min(2.0f, stepped));
    }

    public GuiGraphics graphics() {
        return this.graphics;
    }

    public int guiScale() {
        return this.guiScale;
    }

    public float density() {
        return this.density;
    }

    public int dp(float value) {
        return Math.round(value * this.density);
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.0f, Math.min(1.0f, opacity));
    }

    private int applyOpacity(int color, float coverage) {
        int alpha = Math.round((float)(color >>> 24) * this.opacity * coverage);
        return alpha << 24 | color & 0xFFFFFF;
    }

    public void rect(int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0) {
            this.graphics.fill(x, y, x + w, y + h, this.applyOpacity(color, 1.0f));
        }
    }

    private void shape(int x, int y, int w, int h, int radius, int mode, int color) {
        int c = this.applyOpacity(color, 1.0f);
        if (w <= 0 || h <= 0 || c >>> 24 == 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        ScreenRectangle scissor = ((GuiGraphicsAccessor)this.graphics).visualmod$getScissorStack().peek();
        this.graphics.guiRenderState.submitGuiElement((GuiElementRenderState)ShapeRenderState.create((Matrix3x2fc)new Matrix3x2f((Matrix3x2fc)this.graphics.pose()), x, y, w, h, r, mode, c, scissor));
    }

    public void roundRect(int x, int y, int w, int h, int radius, int color) {
        this.shape(x, y, w, h, radius, 0, color);
    }

    public void roundRectBordered(int x, int y, int w, int h, int radius, int border, int borderColor, int bodyColor) {
        this.roundRect(x, y, w, h, radius, borderColor);
        this.roundRect(x + border, y + border, w - border * 2, h - border * 2, Math.max(0, radius - border), bodyColor);
    }

    public void shadow(int x, int y, int w, int h, int radius, int spread, int color) {
        this.shape(x, y + spread / 3, w, h, radius, Math.max(1, spread / 2), color);
    }

    public void blurCircle(int x, int y, int size, int blur, int color) {
        this.shape(x, y, size, size, size / 2, Math.max(1, blur), color);
    }

    /** Залитый треугольник в пиксельных координатах. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        int c = this.applyOpacity(color, 1.0f);
        if (c >>> 24 == 0) {
            return;
        }
        ScreenRectangle scissor = ((GuiGraphicsAccessor)this.graphics).visualmod$getScissorStack().peek();
        this.graphics.guiRenderState.submitGuiElement((GuiElementRenderState)PolyRenderState.create((Matrix3x2fc)new Matrix3x2f((Matrix3x2fc)this.graphics.pose()), x1, y1, x2, y2, x3, y3, c, scissor));
    }

    public void capsule(float x1, float y1, float x2, float y2, float thickness, int blur, int color) {
        float length = (float)Math.hypot(x2 - x1, y2 - y1);
        int t = Math.max(1, Math.round(thickness));
        this.graphics.pose().pushMatrix();
        this.graphics.pose().translate(x1, y1);
        this.graphics.pose().rotate((float)Math.atan2(y2 - y1, x2 - x1));
        this.shape(-t / 2, -t / 2, Math.round(length) + t, t, t / 2, Math.max(0, blur), color);
        this.graphics.pose().popMatrix();
    }

    public void ribbon(float[][] points, float width, boolean glow, int color) {
        ScreenRectangle scissor = ((GuiGraphicsAccessor)this.graphics).visualmod$getScissorStack().peek();
        RibbonRenderState state = RibbonRenderState.create((Matrix3x2fc)new Matrix3x2f((Matrix3x2fc)this.graphics.pose()), points, width, glow, this.applyOpacity(color, 1.0f), scissor);
        if (state != null) {
            this.graphics.guiRenderState.submitGuiElement((GuiElementRenderState)state);
        }
    }

    public void hueBar(int x, int y, int w, int h) {
        this.shape(x, y, w, h, h / 2, -1, -1);
    }

    public void hueBarFull(int x, int y, int w, int h, int radius) {
        this.shape(x, y, w, h, radius, -3, -1);
    }

    public void saturationValueBox(int x, int y, int w, int h, int radius, int pureHue) {
        this.shape(x, y, w, h, radius, -2, pureHue);
    }

    public void pushClip(int x, int y, int w, int h) {
        this.graphics.enableScissor(x, y, x + Math.max(0, w), y + Math.max(0, h));
    }

    public void popClip() {
        this.graphics.disableScissor();
    }

    private MutableComponent styled(TextStyle style, String text) {
        FontDescription.Resource description = new FontDescription.Resource(Identifier.fromNamespaceAndPath((String)"visualmod", (String)(style.path + this.densitySuffix)));
        return Component.literal((String)text).withStyle(arg_0 -> UiRenderer.lambda$styled$0((FontDescription)description, arg_0));
    }

    public int textWidth(TextStyle style, String text) {
        return this.font.width((FormattedText)this.styled(style, text));
    }

    public int capHeight(TextStyle style) {
        return Math.round((float)style.baseSize * this.density * 0.727f);
    }

    public void text(TextStyle style, String text, int x, int baselineY, int color) {
        int c = this.applyOpacity(color, 1.0f);
        if (c >>> 24 < 4) {
            return;
        }
        this.graphics.drawString(this.font, (Component)this.styled(style, text), x, baselineY - 7, c, false);
    }

    public void textCentered(TextStyle style, String text, int x, int top, int height, int color) {
        this.text(style, text, x, top + (height + this.capHeight(style)) / 2, color);
    }

    public int wrappedText(TextStyle style, String text, int x, int firstBaseline, int width, int lineHeight, int maxLines, int color) {
        int c = this.applyOpacity(color, 1.0f);
        List lines = this.font.split((FormattedText)this.styled(style, text), width);
        int count = Math.min(lines.size(), maxLines);
        if (c >>> 24 >= 4) {
            for (int i = 0; i < count; ++i) {
                this.graphics.drawString(this.font, (FormattedCharSequence)lines.get(i), x, firstBaseline + i * lineHeight - 7, c, false);
            }
        }
        return count;
    }

    public static int lerpColor(int from, int to, float t) {
        int a = UiRenderer.lerpChannel(from >>> 24, to >>> 24, t);
        int r = UiRenderer.lerpChannel(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = UiRenderer.lerpChannel(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = UiRenderer.lerpChannel(from & 0xFF, to & 0xFF, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        return Math.round((float)from + (float)(to - from) * t);
    }

    private static /* synthetic */ Style lambda$styled$0(FontDescription description, Style s) {
        return s.withFont(description);
    }

    public static enum TextStyle {
        TITLE("title", 17),
        NAME("name", 14),
        BODY("body", 12),
        SMALL("small", 11);

        private final String path;
        private final int baseSize;

        private TextStyle(String path, int baseSize) {
            this.path = path;
            this.baseSize = baseSize;
        }
    }
}

