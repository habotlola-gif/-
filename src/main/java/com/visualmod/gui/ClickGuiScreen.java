package com.visualmod.gui;

import com.mojang.blaze3d.platform.Window;
import com.visualmod.VisualModClient;
import com.visualmod.gui.UiRenderer;
import com.visualmod.module.Category;
import com.visualmod.module.Keybind;
import com.visualmod.module.Module;
import com.visualmod.module.ModuleManager;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ButtonSetting;
import com.visualmod.setting.ColorSetting;
import com.visualmod.setting.KeybindSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import com.visualmod.setting.RangeSetting;
import com.visualmod.setting.Setting;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class ClickGuiScreen
extends Screen {
    private static final int PANEL_WIDTH_MAX = 236;
    private static final int PANEL_HEIGHT_MAX = 520;
    private static final int PANEL_GAP = 14;
    private static final int PANEL_RADIUS = 12;
    private static final int SIDE_MARGIN = 16;
    private static final int TITLE_HEIGHT = 46;
    private static final int LIST_PAD_X = 10;
    private static final int LIST_PAD_BOTTOM = 10;
    private static final int ROW_HEIGHT = 34;
    private static final int ROW_GAP = 6;
    private static final int ROW_RADIUS = 8;
    private static final int ROW_PAD_X = 11;
    private static final int SETTINGS_PAD_TOP = 2;
    private static final int SETTINGS_PAD_BOTTOM = 10;
    private static final int SETTING_GAP = 7;
    private static final int LABEL_LINE = 16;
    private static final int CHIP_HEIGHT = 18;
    private static final int CHIP_GAP = 4;
    private static final int SEARCH_WIDTH = 280;
    private static final int SEARCH_HEIGHT = 34;
    private static final int SEARCH_GAP = 16;
    private static final int HINT_HEIGHT = 26;
    private static final int VERTICAL_MARGIN = 20;
    private static final long OPEN_ANIMATION_NANOS = 260000000L;
    private static final long PANEL_STAGGER_NANOS = 35000000L;
    private static final float ANIMATION_SPEED = 16.0f;
    private static final int COLOR_DIM = Integer.MIN_VALUE;
    private static final int COLOR_SHADOW = -1879048192;
    private static final int COLOR_PANEL = -351597809;
    private static final int COLOR_PANEL_BORDER = -14474453;
    private static final int COLOR_ROW = -15461351;
    private static final int COLOR_ROW_HOVER = -15000798;
    private static final int COLOR_ROW_ON = -13027006;
    private static final int COLOR_ROW_BORDER = -14342867;
    private static final int COLOR_ROW_BORDER_ON = -11711144;
    private static final int COLOR_TEXT = -1;
    private static final int COLOR_TEXT_OFF = -3158058;
    private static final int COLOR_MUTED = -7566184;
    private static final int COLOR_FAINT = -10724248;
    private static final int COLOR_CONTROL = -14277074;
    private static final int COLOR_CONTROL_ACTIVE = -10592662;
    private static final int COLOR_SWITCH_KNOB_OFF = -8947841;
    private static final int COLOR_SCROLLBAR = -12961212;
    private final Map<Module, Float> switchProgress = new HashMap<Module, Float>();
    private final Map<Module, Float> expandProgress = new HashMap<Module, Float>();
    private final Map<Setting<?>, Float> settingProgress = new HashMap();
    private final Map<Category, PanelState> panels = new EnumMap<Category, PanelState>(Category.class);
    private final Set<Module> expanded = new HashSet<Module>();
    private final List<Hit> hits = new ArrayList<Hit>();
    private final long openedAt;
    private long lastFrame = this.openedAt = System.nanoTime();
    private String search = "";
    private boolean searchFocused;
    private int searchX;
    private int searchY;
    private int searchW;
    private int searchH;
    private Hit dragging;
    private static final int BIND_OPTION = -2;
    private Module bindingModule;
    private KeybindSetting bindingSetting;
    private boolean swallowChar;
    private int draggingHandle;
    private Module hoveredModule;
    private static final int SWATCH_OPTION = -3;
    private static final int PICKER_WIDTH = 212;
    private static final int[] PRESETS = new int[]{-1096636, -429290, -340971, -11870592, -14494738, -12877066, -7643914, -1292135, -1};
    private ColorSetting picker;
    private float pickerAnim;
    private int pickerAnchorX;
    private int pickerAnchorY;
    private int pickerAnchorW;
    private int pickerDrag;
    private String hexEdit;
    private int[] pickerRect;
    private int[] pickerBox;
    private int[] pickerHue;
    private int[] pickerHex;
    private final int[][] pickerPresets = new int[PRESETS.length][];

    public ClickGuiScreen() {
        super((Component)Component.literal((String)"Visual Mod"));
        for (Module module : ModuleManager.MODULES) {
            this.switchProgress.put(module, Float.valueOf(module.isEnabled() ? 1.0f : 0.0f));
            this.expandProgress.put(module, Float.valueOf(0.0f));
        }
        for (Category category : Category.values()) {
            this.panels.put(category, new PanelState());
        }
    }

    private static float easeOut(float t) {
        float inv = 1.0f - Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f - inv * inv * inv;
    }

    private static float approach(float current, float target, float step) {
        float next = current + (target - current) * step;
        return Math.abs(target - next) < 0.003f ? target : next;
    }

    private float openProgress(int panelIndex) {
        long elapsed = System.nanoTime() - this.openedAt - (long)panelIndex * 35000000L;
        return ClickGuiScreen.easeOut((float)elapsed / 2.6E8f);
    }

    private boolean matchesSearch(Module module) {
        return this.search.isEmpty() || module.getName().toLowerCase(Locale.ROOT).contains(this.search.toLowerCase(Locale.ROOT));
    }

    private List<Module> modulesOf(Category category) {
        ArrayList<Module> result = new ArrayList<Module>();
        for (Module module : ModuleManager.MODULES) {
            if (module.getCategory() != category || !this.matchesSearch(module)) continue;
            result.add(module);
        }
        return result;
    }

    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int alpha = Math.round(128.0f * this.openProgress(0));
        graphics.fill(0, 0, this.width, this.height, alpha << 24);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float seconds = Math.min((float)(now - this.lastFrame) / 1.0E9f, 0.1f);
        this.lastFrame = now;
        float step = 1.0f - (float)Math.exp(-seconds * 16.0f);
        this.swallowChar = false;
        for (Module module : ModuleManager.MODULES) {
            this.switchProgress.put(module, Float.valueOf(ClickGuiScreen.approach(this.switchProgress.get(module).floatValue(), module.isEnabled() ? 1.0f : 0.0f, step)));
            this.expandProgress.put(module, Float.valueOf(ClickGuiScreen.approach(this.expandProgress.get(module).floatValue(), this.expanded.contains(module) ? 1.0f : 0.0f, step)));
        }
        UiRenderer ui = UiRenderer.begin(graphics);
        try {
            this.drawMenu(ui, (int)this.mousePixelX(), (int)this.mousePixelY(), step);
        }
        finally {
            ui.end();
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    private double mousePixelX() {
        Window window = this.minecraft.getWindow();
        return this.minecraft.mouseHandler.xpos() * (double)window.getWidth() / (double)Math.max(1, window.getScreenWidth());
    }

    private double mousePixelY() {
        Window window = this.minecraft.getWindow();
        return this.minecraft.mouseHandler.ypos() * (double)window.getHeight() / (double)Math.max(1, window.getScreenHeight());
    }

    private void drawMenu(UiRenderer ui, int mouseX, int mouseY, float step) {
        this.hits.clear();
        this.hoveredModule = null;
        int screenW = this.width * ui.guiScale();
        int screenH = this.height * ui.guiScale();
        int count = Category.values().length;
        int gap = ui.dp(14.0f);
        int panelW = Math.min(ui.dp(236.0f), (screenW - ui.dp(16.0f) * 2 - gap * (count - 1)) / count);
        int bottomBlock = ui.dp(76.0f);
        int panelH = Math.min(ui.dp(520.0f), screenH - ui.dp(20.0f) * 2 - bottomBlock);
        int blockH = panelH + bottomBlock;
        int left = (screenW - (panelW * count + gap * (count - 1))) / 2;
        int top = Math.max(ui.dp(20.0f), (screenH - blockH) / 2);
        for (int i = 0; i < count; ++i) {
            Category category = Category.values()[i];
            float open = this.openProgress(i);
            ui.setOpacity(open);
            int x = left + i * (panelW + gap);
            int y = top + Math.round((1.0f - open) * (float)ui.dp(24.0f));
            this.drawPanel(ui, category, x, y, panelW, panelH, mouseX, mouseY, step);
        }
        float open = this.openProgress(count / 2);
        ui.setOpacity(open);
        this.searchW = ui.dp(280.0f);
        this.searchH = ui.dp(34.0f);
        this.searchX = (screenW - this.searchW) / 2;
        this.searchY = top + panelH + ui.dp(16.0f) + Math.round((1.0f - open) * (float)ui.dp(24.0f));
        this.drawSearch(ui, mouseX, mouseY);
        Object hint = this.bindingSetting != null ? "\u0411\u0438\u043d\u0434 \u00ab" + this.bindingSetting.getName() + "\u00bb: \u043d\u0430\u0436\u043c\u0438 \u043a\u043b\u0430\u0432\u0438\u0448\u0443 \u0438\u043b\u0438 \u043a\u043d\u043e\u043f\u043a\u0443 \u043c\u044b\u0448\u0438   \u00b7   Esc \u2014 \u043e\u0442\u043c\u0435\u043d\u0430   \u00b7   Backspace \u2014 \u0443\u0431\u0440\u0430\u0442\u044c" : (this.bindingModule != null ? "\u0411\u0438\u043d\u0434 \u0434\u043b\u044f " + this.bindingModule.getName() + ": \u043d\u0430\u0436\u043c\u0438 \u043a\u043b\u0430\u0432\u0438\u0448\u0443 \u0438\u043b\u0438 \u043a\u043d\u043e\u043f\u043a\u0443 \u043c\u044b\u0448\u0438   \u00b7   Esc \u2014 \u043e\u0442\u043c\u0435\u043d\u0430   \u00b7   Backspace \u2014 \u0443\u0431\u0440\u0430\u0442\u044c" : (this.hoveredModule != null ? this.hoveredModule.getDescription() : "\u041b\u041a\u041c \u2014 \u0432\u043a\u043b / \u0432\u044b\u043a\u043b   \u00b7   \u041f\u041a\u041c \u2014 \u043d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u0438 \u0431\u0438\u043d\u0434   \u00b7   RShift \u2014 \u0437\u0430\u043a\u0440\u044b\u0442\u044c"));
        int hintW = ui.textWidth(UiRenderer.TextStyle.SMALL, (String)hint);
        ui.textCentered(UiRenderer.TextStyle.SMALL, (String)hint, (screenW - hintW) / 2, this.searchY + this.searchH, ui.dp(26.0f), this.hoveredModule != null ? -1 : -3158058);
        ui.setOpacity(1.0f);
        this.drawPicker(ui, mouseX, mouseY, screenW, screenH, step);
    }

    private void drawPanel(UiRenderer ui, Category category, int x, int y, int w, int h, int mouseX, int mouseY, float step) {
        PanelState panel = this.panels.get((Object)category);
        int border = Math.max(1, ui.dp(1.0f));
        ui.shadow(x, y, w, h, ui.dp(12.0f), ui.dp(18.0f), -1879048192);
        ui.roundRectBordered(x, y, w, h, ui.dp(12.0f), border, -14474453, -351597809);
        String title = category.getTitle();
        int titleW = ui.textWidth(UiRenderer.TextStyle.TITLE, title);
        ui.textCentered(UiRenderer.TextStyle.TITLE, title, x + (w - titleW) / 2, y, ui.dp(46.0f), -1);
        int listTop = y + ui.dp(46.0f);
        int listBottom = y + h - ui.dp(10.0f);
        panel.x = x;
        panel.width = w;
        panel.listTop = listTop;
        panel.listBottom = listBottom;
        List<Module> modules = this.modulesOf(category);
        int rowX = x + ui.dp(10.0f);
        int rowW = w - ui.dp(10.0f) * 2;
        int contentH = 0;
        for (Module module : modules) {
            contentH += this.rowContainerHeight(ui, module, rowW) + ui.dp(6.0f);
        }
        contentH = Math.max(0, contentH - ui.dp(6.0f));
        panel.maxScroll = Math.max(0, contentH - (listBottom - listTop));
        panel.scrollTarget = Math.max(0.0f, Math.min((float)panel.maxScroll, panel.scrollTarget));
        panel.scroll = Math.max(0.0f, Math.min((float)panel.maxScroll, ClickGuiScreen.approach(panel.scroll, panel.scrollTarget, step)));
        if (modules.isEmpty()) {
            String empty = this.search.isEmpty() ? "\u041f\u0443\u0441\u0442\u043e" : "\u041d\u0438\u0447\u0435\u0433\u043e \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u043e";
            int emptyW = ui.textWidth(UiRenderer.TextStyle.SMALL, empty);
            ui.textCentered(UiRenderer.TextStyle.SMALL, empty, x + (w - emptyW) / 2, listTop, ui.dp(34.0f), -10724248);
            return;
        }
        ui.pushClip(x, listTop, w, listBottom - listTop);
        int rowY = listTop - Math.round(panel.scroll);
        for (Module module : modules) {
            int containerH = this.rowContainerHeight(ui, module, rowW);
            if (rowY + containerH > listTop && rowY < listBottom) {
                this.drawRow(ui, module, rowX, rowY, rowW, containerH, mouseX, mouseY, listTop, listBottom, step);
            }
            rowY += containerH + ui.dp(6.0f);
        }
        ui.popClip();
        if (panel.maxScroll > 0) {
            int trackTop = listTop + ui.dp(2.0f);
            int trackH = listBottom - listTop - ui.dp(4.0f);
            int thumbH = Math.max(ui.dp(24.0f), Math.round((float)trackH * ((float)trackH / (float)contentH)));
            int thumbY = trackTop + Math.round((float)(trackH - thumbH) * (panel.scroll / (float)panel.maxScroll));
            int barW = Math.max(2, ui.dp(3.0f));
            ui.roundRect(x + w - ui.dp(5.0f), thumbY, barW, thumbH, barW / 2, -12961212);
        }
    }

    private int rowContainerHeight(UiRenderer ui, Module module, int rowW) {
        float e = ClickGuiScreen.easeOut(this.expandProgress.get(module).floatValue());
        if (e <= 0.0f) {
            return ui.dp(34.0f);
        }
        return ui.dp(34.0f) + Math.round((float)this.settingsHeight(ui, module, rowW) * e);
    }

    private int settingsHeight(UiRenderer ui, Module module, int rowW) {
        int innerW = rowW - ui.dp(11.0f) * 2;
        int h = ui.dp(12.0f) + this.bindRowHeight(ui);
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isHidden()) continue;
            h += ui.dp(7.0f) + this.settingHeight(ui, setting, innerW);
        }
        return h;
    }

    private int bindRowHeight(UiRenderer ui) {
        return ui.dp(20.0f);
    }

    private int settingHeight(UiRenderer ui, Setting<?> setting, int innerW) {
        if (setting instanceof ButtonSetting) {
            return ui.dp(24.0f);
        }
        if (setting instanceof BooleanSetting || setting instanceof KeybindSetting) {
            return ui.dp(18.0f);
        }
        if (setting instanceof ModeSetting) {
            ModeSetting mode = (ModeSetting)setting;
            return ui.dp(19.0f) + this.chipRows(ui, mode, innerW) * ui.dp(22.0f) - ui.dp(4.0f);
        }
        if (setting instanceof NumberSetting || setting instanceof RangeSetting) {
            return ui.dp(32.0f);
        }
        return ui.dp(30.0f);
    }

    private int chipWidth(UiRenderer ui, String option) {
        return ui.textWidth(UiRenderer.TextStyle.SMALL, option) + ui.dp(12.0f);
    }

    private int chipRows(UiRenderer ui, ModeSetting mode, int innerW) {
        int rows = 1;
        int lineW = 0;
        for (String option : mode.getModes()) {
            int cw = this.chipWidth(ui, option);
            if (lineW > 0 && lineW + ui.dp(4.0f) + cw > innerW) {
                ++rows;
                lineW = 0;
            }
            lineW += (lineW > 0 ? ui.dp(4.0f) : 0) + cw;
        }
        return rows;
    }

    private void drawRow(UiRenderer ui, Module module, int x, int y, int w, int h, int mouseX, int mouseY, int clipTop, int clipBottom, float step) {
        boolean hovered;
        float on = this.switchProgress.get(module).floatValue();
        int headerH = ui.dp(34.0f);
        boolean mouseInList = mouseY >= clipTop && mouseY < clipBottom;
        boolean bl = hovered = this.dragging == null && mouseInList && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + headerH;
        if (hovered) {
            this.hoveredModule = module;
        }
        int base = hovered ? -15000798 : -15461351;
        int body = UiRenderer.lerpColor(base, -13027006, on);
        int border = UiRenderer.lerpColor(-14342867, -11711144, on);
        ui.roundRectBordered(x, y, w, h, ui.dp(8.0f), Math.max(1, ui.dp(1.0f)), border, body);
        int padX = ui.dp(11.0f);
        int dot = Math.max(2, ui.dp(3.0f));
        int dotGap = Math.max(1, ui.dp(2.0f));
        int dotsW = dot * 3 + dotGap * 2;
        int dotsX = x + w - padX - dotsW;
        int bindW = 0;
        if (module.getBind() != -1) {
            String bindText = Keybind.displayName(module.getBind());
            bindW = ui.textWidth(UiRenderer.TextStyle.SMALL, bindText) + ui.dp(8.0f);
            ui.textCentered(UiRenderer.TextStyle.SMALL, bindText, dotsX - bindW, y, headerH, -10724248);
        }
        ui.pushClip(x + padX, Math.max(y, clipTop), w - padX * 2 - dotsW - bindW - ui.dp(6.0f), Math.min(y + headerH, clipBottom) - Math.max(y, clipTop));
        ui.textCentered(UiRenderer.TextStyle.NAME, module.getName(), x + padX, y, headerH, UiRenderer.lerpColor(-3158058, -1, on));
        ui.popClip();
        int dotsY = y + (headerH - dot) / 2;
        float e = this.expandProgress.get(module).floatValue();
        int dotsColor = UiRenderer.lerpColor(-7566184, -1, Math.max(e, hovered ? 0.4f : 0.0f));
        for (int i = 0; i < 3; ++i) {
            ui.roundRect(dotsX + i * (dot + dotGap), dotsY, dot, dot, dot / 2, dotsColor);
        }
        int visibleTop = Math.max(y, clipTop);
        int visibleBottom = Math.min(y + headerH, clipBottom);
        if (visibleBottom > visibleTop) {
            int dotsHitW = dotsW + padX * 2;
            this.hits.add(new Hit(x + w - dotsHitW, visibleTop, dotsHitW, visibleBottom - visibleTop, module, null, 0, 0, -1, true));
            this.hits.add(new Hit(x, visibleTop, w - dotsHitW, visibleBottom - visibleTop, module, null, 0, 0, -1, false));
        }
        if (h > headerH) {
            this.drawSettings(ui, module, x, y + headerH, w, h - headerH, mouseX, mouseY, Math.max(clipTop, y + headerH), Math.min(clipBottom, y + h), step);
        }
    }

    private void drawBindRow(UiRenderer ui, Module module, int rowX, int x, int y, int w, int h, int mouseX, int mouseY, int clipTop, int clipBottom) {
        int bg;
        boolean listening = this.bindingModule == module;
        boolean hovered = !listening && mouseX >= rowX && mouseX < x + w && mouseY >= Math.max(y, clipTop) && mouseY < Math.min(y + h, clipBottom);
        ui.textCentered(UiRenderer.TextStyle.SMALL, "\u0411\u0438\u043d\u0434", x, y, h, hovered || listening ? -1 : -7566184);
        String value = listening ? "\u041d\u0430\u0436\u043c\u0438 \u043a\u043d\u043e\u043f\u043a\u0443\u2026" : Keybind.displayName(module.getBind());
        int pad = ui.dp(7.0f);
        int pillH = ui.dp(16.0f);
        int pillW = ui.textWidth(UiRenderer.TextStyle.SMALL, value) + pad * 2;
        int pillX = x + w - pillW;
        int pillY = y + (h - pillH) / 2;
        if (listening) {
            float pulse = 0.5f + 0.5f * (float)Math.sin((double)System.nanoTime() / 1.0E9 * 6.0);
            bg = UiRenderer.lerpColor(-14277074, -10592662, pulse);
        } else {
            bg = hovered ? -13684936 : -14277074;
        }
        ui.roundRect(pillX, pillY, pillW, pillH, ui.dp(5.0f), bg);
        int textColor = listening || module.getBind() != -1 ? -1 : -7566184;
        ui.textCentered(UiRenderer.TextStyle.SMALL, value, pillX + pad, pillY, pillH, textColor);
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            this.hits.add(new Hit(rowX, top, x + w - rowX + ui.dp(11.0f), bottom - top, module, null, 0, 0, -2, false));
        }
    }

    private void drawSettings(UiRenderer ui, Module module, int x, int y, int w, int visibleH, int mouseX, int mouseY, int clipTop, int clipBottom, float step) {
        if (clipBottom <= clipTop) {
            return;
        }
        ui.pushClip(x, clipTop, w, clipBottom - clipTop);
        int padX = ui.dp(11.0f);
        int innerX = x + padX;
        int innerW = w - padX * 2;
        int fullH = this.settingsHeight(ui, module, w);
        int cursor = y + ui.dp(2.0f) - (fullH - visibleH) / 3;
        int bindH = this.bindRowHeight(ui);
        this.drawBindRow(ui, module, x, innerX, cursor, innerW, bindH, mouseX, mouseY, clipTop, clipBottom);
        cursor += bindH + ui.dp(7.0f);
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isHidden()) continue;
            int sh = this.settingHeight(ui, setting, innerW);
            boolean hovered = (this.dragging == null || this.dragging.setting() == setting) && mouseX >= x && mouseX < x + w && mouseY >= Math.max(cursor, clipTop) && mouseY < Math.min(cursor + sh, clipBottom);
            this.drawSetting(ui, module, setting, innerX, cursor, innerW, sh, hovered, mouseX, mouseY, clipTop, clipBottom, step);
            cursor += sh + ui.dp(7.0f);
        }
        ui.popClip();
    }

    private void drawSetting(UiRenderer ui, Module module, Setting<?> setting, int x, int y, int w, int h, boolean hovered, int mouseX, int mouseY, int clipTop, int clipBottom, float step) {
        int labelColor = hovered ? -1 : -7566184;
        int labelLine = ui.dp(16.0f);
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (setting instanceof BooleanSetting) {
            BooleanSetting bool = (BooleanSetting)setting;
            float target = bool.isOn() ? 1.0f : 0.0f;
            float p = ClickGuiScreen.approach(this.settingProgress.getOrDefault(setting, Float.valueOf(target)).floatValue(), target, step);
            this.settingProgress.put(setting, Float.valueOf(p));
            ui.textCentered(UiRenderer.TextStyle.SMALL, setting.getName(), x, y, h, labelColor);
            int sw = ui.dp(26.0f);
            int sh = ui.dp(14.0f);
            int sx = x + w - sw;
            int sy = y + (h - sh) / 2;
            ui.roundRect(sx, sy, sw, sh, sh / 2, UiRenderer.lerpColor(-14277074, -1, p));
            int knob = sh - ui.dp(4.0f);
            int knobX = sx + ui.dp(2.0f) + Math.round((float)(sw - ui.dp(4.0f) - knob) * p);
            ui.roundRect(knobX, sy + ui.dp(2.0f), knob, knob, knob / 2, UiRenderer.lerpColor(-8947841, -16777216, p));
            this.addSettingHit(module, setting, x, top, w, bottom - top, 0, 0, -1);
            return;
        }
        if (setting instanceof ButtonSetting) {
            int by = y + ui.dp(1.0f);
            int bh = h - ui.dp(2.0f);
            ui.roundRect(x, by, w, bh, ui.dp(6.0f), hovered ? -13684936 : -14277074);
            int tw = ui.textWidth(UiRenderer.TextStyle.SMALL, setting.getName());
            ui.textCentered(UiRenderer.TextStyle.SMALL, setting.getName(), x + (w - tw) / 2, by, bh, hovered ? -1 : -3158058);
            this.addSettingHit(module, setting, x, top, w, bottom - top, 0, 0, -4);
            return;
        }
        if (setting instanceof KeybindSetting) {
            int bg;
            KeybindSetting keybind = (KeybindSetting)setting;
            boolean listening = this.bindingSetting == keybind;
            ui.textCentered(UiRenderer.TextStyle.SMALL, setting.getName(), x, y, h, hovered || listening ? -1 : -7566184);
            String value = listening ? "\u041d\u0430\u0436\u043c\u0438 \u043a\u043d\u043e\u043f\u043a\u0443\u2026" : Keybind.displayName(keybind.getBind());
            int pad = ui.dp(7.0f);
            int pillH = ui.dp(16.0f);
            int pillW = ui.textWidth(UiRenderer.TextStyle.SMALL, value) + pad * 2;
            int pillX = x + w - pillW;
            int pillY = y + (h - pillH) / 2;
            if (listening) {
                float pulse = 0.5f + 0.5f * (float)Math.sin((double)System.nanoTime() / 1.0E9 * 6.0);
                bg = UiRenderer.lerpColor(-14277074, -10592662, pulse);
            } else {
                bg = hovered ? -13684936 : -14277074;
            }
            ui.roundRect(pillX, pillY, pillW, pillH, ui.dp(5.0f), bg);
            int textColor = listening || keybind.getBind() != -1 ? -1 : -7566184;
            ui.textCentered(UiRenderer.TextStyle.SMALL, value, pillX + pad, pillY, pillH, textColor);
            this.addSettingHit(module, setting, x, top, w, bottom - top, 0, 0, -2);
            return;
        }
        ui.textCentered(UiRenderer.TextStyle.SMALL, setting.getName(), x, y, labelLine, labelColor);
        if (setting instanceof ModeSetting) {
            ModeSetting mode = (ModeSetting)setting;
            int chipH = ui.dp(18.0f);
            int cx = x;
            int cy = y + ui.dp(19.0f);
            for (int i = 0; i < mode.getModes().size(); ++i) {
                boolean chipHovered;
                String option = mode.getModes().get(i);
                int cw = this.chipWidth(ui, option);
                if (cx > x && cx + cw > x + w) {
                    cx = x;
                    cy += chipH + ui.dp(4.0f);
                }
                boolean selected = mode.getIndex() == i;
                boolean bl = chipHovered = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + chipH && mouseY >= clipTop && mouseY < clipBottom;
                int bg = selected ? -10592662 : (chipHovered ? -13684936 : -14277074);
                ui.roundRect(cx, cy, cw, chipH, ui.dp(5.0f), bg);
                ui.textCentered(UiRenderer.TextStyle.SMALL, option, cx + ui.dp(6.0f), cy, chipH, selected ? -1 : -7566184);
                int chipTop = Math.max(cy, clipTop);
                int chipBottom = Math.min(cy + chipH, clipBottom);
                if (chipBottom > chipTop) {
                    this.addSettingHit(module, setting, cx, chipTop, cw, chipBottom - chipTop, 0, 0, i);
                }
                cx += cw + ui.dp(4.0f);
            }
            return;
        }
        if (setting instanceof NumberSetting) {
            NumberSetting number = (NumberSetting)setting;
            String value = number.displayValue();
            int badgePad = ui.dp(5.0f);
            int badgeH = ui.dp(14.0f);
            int badgeW = ui.textWidth(UiRenderer.TextStyle.SMALL, value) + badgePad * 2;
            int badgeX = x + w - badgeW;
            int badgeY = y + (labelLine - badgeH) / 2;
            ui.roundRect(badgeX, badgeY, badgeW, badgeH, ui.dp(4.0f), -14277074);
            ui.textCentered(UiRenderer.TextStyle.SMALL, value, badgeX + badgePad, badgeY, badgeH, -3158058);
            float target = (float)number.getProgress();
            boolean dragged = this.dragging != null && this.dragging.setting() == setting;
            float p = dragged ? target : ClickGuiScreen.approach(this.settingProgress.getOrDefault(setting, Float.valueOf(target)).floatValue(), target, step);
            this.settingProgress.put(setting, Float.valueOf(p));
            int trackH = Math.max(2, ui.dp(4.0f));
            int trackY = y + labelLine + ui.dp(8.0f);
            int knob = ui.dp(11.0f);
            ui.roundRect(x, trackY, w, trackH, trackH / 2, -14277074);
            ui.roundRect(x, trackY, Math.max(trackH, Math.round((float)w * p)), trackH, trackH / 2, -6645082);
            int knobX = x + Math.round((float)(w - knob) * p);
            ui.roundRect(knobX, trackY + trackH / 2 - knob / 2, knob, knob, knob / 2, -1);
            this.addSettingHit(module, setting, x, top, w, bottom - top, x, w, -1);
            return;
        }
        if (setting instanceof RangeSetting) {
            RangeSetting range = (RangeSetting)setting;
            String value = range.displayValue();
            int badgePad = ui.dp(5.0f);
            int badgeH = ui.dp(14.0f);
            int badgeW = ui.textWidth(UiRenderer.TextStyle.SMALL, value) + badgePad * 2;
            int badgeX = x + w - badgeW;
            int badgeY = y + (labelLine - badgeH) / 2;
            ui.roundRect(badgeX, badgeY, badgeW, badgeH, ui.dp(4.0f), -14277074);
            ui.textCentered(UiRenderer.TextStyle.SMALL, value, badgeX + badgePad, badgeY, badgeH, -3158058);
            int trackH = Math.max(2, ui.dp(4.0f));
            int trackY = y + labelLine + ui.dp(8.0f);
            int knob = ui.dp(11.0f);
            int lowX = x + Math.round((float)(w - knob) * (float)range.lowProgress());
            int highX = x + Math.round((float)(w - knob) * (float)range.highProgress());
            ui.roundRect(x, trackY, w, trackH, trackH / 2, -14277074);
            ui.roundRect(lowX + knob / 2, trackY, Math.max(trackH, highX - lowX), trackH, trackH / 2, -6645082);
            ui.roundRect(lowX, trackY + trackH / 2 - knob / 2, knob, knob, knob / 2, -1);
            ui.roundRect(highX, trackY + trackH / 2 - knob / 2, knob, knob, knob / 2, -1);
            this.addSettingHit(module, setting, x, top, w, bottom - top, x, w, -1);
            return;
        }
        if (setting instanceof ColorSetting) {
            boolean swatchHovered;
            ColorSetting color = (ColorSetting)setting;
            int swatch = ui.dp(12.0f);
            int swatchX = x + w - swatch;
            int swatchY = y + (labelLine - swatch) / 2;
            int grow = ui.dp(5.0f);
            boolean bl = swatchHovered = mouseX >= swatchX - grow && mouseX < swatchX + swatch + grow && mouseY >= swatchY - grow && mouseY < swatchY + swatch + grow && mouseY >= clipTop && mouseY < clipBottom;
            if (swatchHovered || this.picker == color) {
                int ring = Math.max(1, ui.dp(2.0f));
                ui.roundRect(swatchX - ring, swatchY - ring, swatch + ring * 2, swatch + ring * 2, swatch / 2 + ring, -1);
            }
            ui.roundRect(swatchX, swatchY, swatch, swatch, swatch / 2, color.rgb());
            int barH = ui.dp(6.0f);
            int barY = y + labelLine + ui.dp(5.0f);
            ui.hueBar(x, barY, w, barH);
            int knob = ui.dp(12.0f);
            int knobX = x + Math.round((float)(w - knob) * color.getProgress());
            int knobY = barY + barH / 2 - knob / 2;
            int ring = Math.max(1, ui.dp(2.0f));
            ui.roundRect(knobX, knobY, knob, knob, knob / 2, -1);
            ui.roundRect(knobX + ring, knobY + ring, knob - ring * 2, knob - ring * 2, knob / 2 - ring, color.rgb());
            this.addSettingHit(module, setting, x, top, w, bottom - top, x, w, -1);
            int swatchTop = Math.max(swatchY - grow, clipTop);
            int swatchBottom = Math.min(swatchY + swatch + grow, clipBottom);
            if (swatchBottom > swatchTop) {
                this.addSettingHit(module, setting, swatchX - grow, swatchTop, swatch + grow * 2, swatchBottom - swatchTop, 0, 0, -3);
            }
        }
    }

    private void drawPicker(UiRenderer ui, int mouseX, int mouseY, int screenW, int screenH, float step) {
        this.pickerAnim = ClickGuiScreen.approach(this.pickerAnim, this.picker != null ? 1.0f : 0.0f, step);
        if (this.picker == null) {
            this.pickerRect = null;
            return;
        }
        ColorSetting color = this.picker;
        int pad = ui.dp(12.0f);
        int w = ui.dp(212.0f);
        int innerW = w - pad * 2;
        int headerH = ui.dp(20.0f);
        int boxH = ui.dp(124.0f);
        int hueH = ui.dp(10.0f);
        int preset = ui.dp(16.0f);
        int gap = ui.dp(10.0f);
        int h = pad + headerH + ui.dp(6.0f) + boxH + gap + hueH + gap + preset + pad;
        int x = this.pickerAnchorX + ui.dp(10.0f);
        if (x + w > screenW - ui.dp(8.0f)) {
            x = this.pickerAnchorX - this.pickerAnchorW - ui.dp(10.0f) - w;
        }
        x = Math.max(ui.dp(8.0f), Math.min(screenW - w - ui.dp(8.0f), x));
        int y = Math.max(ui.dp(8.0f), Math.min(screenH - h - ui.dp(8.0f), this.pickerAnchorY - ui.dp(14.0f)));
        this.pickerRect = new int[]{x, y, w, h};
        float open = ClickGuiScreen.easeOut(this.pickerAnim);
        ui.setOpacity(open);
        ui.shadow(x, y += Math.round((1.0f - open) * (float)ui.dp(6.0f)), w, h, ui.dp(12.0f), ui.dp(20.0f), -1879048192);
        ui.roundRectBordered(x, y, w, h, ui.dp(12.0f), Math.max(1, ui.dp(1.0f)), -11711144, -351597809);
        int cy = y + pad;
        ui.textCentered(UiRenderer.TextStyle.NAME, color.getName(), x + pad, cy, headerH, -1);
        String hexText = this.hexEdit != null ? "#" + this.hexEdit + (System.currentTimeMillis() / 500L % 2L == 0L ? "|" : " ") : color.hex();
        int hexPad = ui.dp(6.0f);
        int hexW = Math.max(ui.textWidth(UiRenderer.TextStyle.SMALL, "#000000|"), ui.textWidth(UiRenderer.TextStyle.SMALL, hexText)) + hexPad * 2;
        int hexH = ui.dp(18.0f);
        int hexX = x + w - pad - hexW;
        int hexY = cy + (headerH - hexH) / 2;
        boolean hexHovered = mouseX >= hexX && mouseX < hexX + hexW && mouseY >= hexY && mouseY < hexY + hexH;
        ui.roundRect(hexX, hexY, hexW, hexH, ui.dp(5.0f), this.hexEdit != null ? -10592662 : (hexHovered ? -13684936 : -14277074));
        ui.textCentered(UiRenderer.TextStyle.SMALL, hexText, hexX + hexPad, hexY, hexH, this.hexEdit != null ? -1 : -3158058);
        this.pickerHex = new int[]{hexX, hexY, hexW, hexH};
        int dot = ui.dp(12.0f);
        ui.roundRect(hexX - ui.dp(6.0f) - dot, cy + (headerH - dot) / 2, dot, dot, dot / 2, color.rgb());
        ui.saturationValueBox(x + pad, cy += headerH + ui.dp(6.0f), innerW, boxH, ui.dp(6.0f), ColorSetting.pureHue(color.getHue()));
        this.pickerBox = new int[]{x + pad, cy, innerW, boxH};
        int cursor = ui.dp(12.0f);
        int ring = Math.max(1, ui.dp(2.0f));
        int curX = x + pad + Math.round((float)innerW * color.getSaturation()) - cursor / 2;
        int curY = cy + Math.round((float)boxH * (1.0f - color.getBrightness())) - cursor / 2;
        ui.shadow(curX, curY, cursor, cursor, cursor / 2, ui.dp(4.0f), 0x60000000);
        ui.roundRect(curX, curY, cursor, cursor, cursor / 2, -1);
        ui.roundRect(curX + ring, curY + ring, cursor - ring * 2, cursor - ring * 2, cursor / 2 - ring, color.rgb());
        ui.hueBarFull(x + pad, cy += boxH + gap, innerW, hueH, hueH / 2);
        this.pickerHue = new int[]{x + pad, cy - ui.dp(4.0f), innerW, hueH + ui.dp(8.0f)};
        int knob = ui.dp(14.0f);
        int knobX = x + pad + Math.round((float)(innerW - knob) * color.getProgress());
        int knobY = cy + hueH / 2 - knob / 2;
        ui.roundRect(knobX, knobY, knob, knob, knob / 2, -1);
        ui.roundRect(knobX + ring, knobY + ring, knob - ring * 2, knob - ring * 2, knob / 2 - ring, ColorSetting.pureHue(color.getHue()));
        cy += hueH + gap;
        int presetGap = (innerW - PRESETS.length * preset) / Math.max(1, PRESETS.length - 1);
        for (int i = 0; i < PRESETS.length; ++i) {
            boolean selected;
            int px = x + pad + i * (preset + presetGap);
            boolean hovered = mouseX >= px && mouseX < px + preset && mouseY >= cy && mouseY < cy + preset;
            boolean bl = selected = (color.rgb() & 0xFFFFFF) == (PRESETS[i] & 0xFFFFFF);
            if (hovered || selected) {
                ui.roundRect(px - ring, cy - ring, preset + ring * 2, preset + ring * 2, preset / 2 + ring, selected ? -1 : -7566184);
            }
            ui.roundRect(px, cy, preset, preset, preset / 2, PRESETS[i]);
            this.pickerPresets[i] = new int[]{px, cy, preset, preset};
        }
        ui.setOpacity(1.0f);
    }

    private static boolean inside(int[] rect, double px, double py) {
        return rect != null && px >= (double)rect[0] && px < (double)(rect[0] + rect[2]) && py >= (double)rect[1] && py < (double)(rect[1] + rect[3]);
    }

    private void openPicker(ColorSetting color, Hit swatch) {
        if (this.picker == color) {
            this.closePicker();
            return;
        }
        this.picker = color;
        this.pickerAnim = 0.0f;
        this.hexEdit = null;
        this.pickerAnchorX = swatch.x() + swatch.w();
        this.pickerAnchorY = swatch.y();
        this.pickerAnchorW = swatch.w();
    }

    private void closePicker() {
        if (this.picker != null) {
            ModuleManager.save();
        }
        this.picker = null;
        this.pickerDrag = 0;
        this.hexEdit = null;
    }

    private void applyPickerDrag(double px, double py) {
        if (this.picker == null) {
            return;
        }
        if (this.pickerDrag == 1 && this.pickerBox != null) {
            this.picker.setSaturation((float)((px - (double)this.pickerBox[0]) / (double)Math.max(1, this.pickerBox[2])));
            this.picker.setBrightness(1.0f - (float)((py - (double)this.pickerBox[1]) / (double)Math.max(1, this.pickerBox[3])));
        } else if (this.pickerDrag == 2 && this.pickerHue != null) {
            this.picker.setProgress(Math.max(0.0, Math.min(1.0, (px - (double)this.pickerHue[0]) / (double)Math.max(1, this.pickerHue[2]))));
        }
    }

    private boolean pickerClicked(double px, double py) {
        if (!ClickGuiScreen.inside(this.pickerRect, px, py)) {
            this.closePicker();
            return false;
        }
        if (this.hexEdit != null && !ClickGuiScreen.inside(this.pickerHex, px, py)) {
            this.hexEdit = null;
        }
        if (ClickGuiScreen.inside(this.pickerBox, px, py)) {
            this.pickerDrag = 1;
        } else if (ClickGuiScreen.inside(this.pickerHue, px, py)) {
            this.pickerDrag = 2;
        } else if (ClickGuiScreen.inside(this.pickerHex, px, py)) {
            this.hexEdit = this.hexEdit == null ? "" : null;
        } else {
            for (int i = 0; i < PRESETS.length; ++i) {
                if (!ClickGuiScreen.inside(this.pickerPresets[i], px, py)) continue;
                this.picker.setRgb(PRESETS[i]);
                ModuleManager.save();
            }
        }
        this.applyPickerDrag(px, py);
        return true;
    }

    private void applyHex() {
        if (this.picker != null && this.hexEdit != null && (this.hexEdit.length() == 6 || this.hexEdit.length() == 3)) {
            String full = this.hexEdit.length() == 3 ? "" + this.hexEdit.charAt(0) + this.hexEdit.charAt(0) + this.hexEdit.charAt(1) + this.hexEdit.charAt(1) + this.hexEdit.charAt(2) + this.hexEdit.charAt(2) : this.hexEdit;
            this.picker.setRgb(Integer.parseInt(full, 16));
            ModuleManager.save();
        }
        this.hexEdit = null;
    }

    private void addSettingHit(Module module, Setting<?> setting, int x, int y, int w, int h, int trackX, int trackW, int option) {
        if (h > 0) {
            this.hits.add(new Hit(x, y, w, h, module, setting, trackX, trackW, option, false));
        }
    }

    private void drawSearch(UiRenderer ui, int mouseX, int mouseY) {
        boolean hovered;
        boolean bl = hovered = mouseX >= this.searchX && mouseX < this.searchX + this.searchW && mouseY >= this.searchY && mouseY < this.searchY + this.searchH;
        int border = this.searchFocused ? -11711144 : (hovered ? -13421763 : -14474453);
        ui.roundRectBordered(this.searchX, this.searchY, this.searchW, this.searchH, ui.dp(9.0f), Math.max(1, ui.dp(1.0f)), border, -351597809);
        int padX = ui.dp(12.0f);
        if (this.search.isEmpty()) {
            ui.textCentered(UiRenderer.TextStyle.BODY, "\u041f\u043e\u0438\u0441\u043a", this.searchX + padX, this.searchY, this.searchH, -10724248);
        } else {
            ui.textCentered(UiRenderer.TextStyle.BODY, this.search, this.searchX + padX, this.searchY, this.searchH, -1);
        }
        if (this.searchFocused && System.currentTimeMillis() / 500L % 2L == 0L) {
            int caretX = this.searchX + padX + (this.search.isEmpty() ? 0 : ui.textWidth(UiRenderer.TextStyle.BODY, this.search) + ui.dp(1.0f));
            int caretH = ui.dp(14.0f);
            ui.rect(caretX, this.searchY + (this.searchH - caretH) / 2, Math.max(1, ui.dp(1.0f)), caretH, -1);
        }
    }

    private void setSearch(String text) {
        this.search = text;
        for (PanelState panel : this.panels.values()) {
            panel.scrollTarget = 0.0f;
        }
    }

    private Hit hitAt(double px, double py) {
        for (int i = this.hits.size() - 1; i >= 0; --i) {
            if (!this.hits.get(i).contains(px, py)) continue;
            return this.hits.get(i);
        }
        return null;
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Setting<?> setting;
        double px = this.mousePixelX();
        double py = this.mousePixelY();
        if (this.bindingModule != null) {
            if (event.button() > 1) {
                this.bindingModule.setBind(Keybind.mouse(event.button()));
                ModuleManager.save();
            }
            this.bindingModule = null;
            return true;
        }
        if (this.bindingSetting != null) {
            if (event.button() > 1) {
                this.bindingSetting.setBind(Keybind.mouse(event.button()));
                ModuleManager.save();
            }
            this.bindingSetting = null;
            return true;
        }
        if (this.picker != null && this.pickerClicked(px, py)) {
            return true;
        }
        boolean bl = this.searchFocused = px >= (double)this.searchX && px < (double)(this.searchX + this.searchW) && py >= (double)this.searchY && py < (double)(this.searchY + this.searchH);
        if (this.searchFocused) {
            return true;
        }
        Hit hit = this.hitAt(px, py);
        if (hit == null) {
            return super.mouseClicked(event, doubleClick);
        }
        int button = event.button();
        Module module = hit.module();
        if (hit.option() == -3 && (setting = hit.setting()) instanceof ColorSetting) {
            ColorSetting color = (ColorSetting)setting;
            this.openPicker(color, hit);
            return true;
        }
        if (hit.setting() == null && hit.option() == -2) {
            if (button == 0) {
                this.bindingModule = module;
            } else if (button == 1) {
                module.setBind(-1);
                ModuleManager.save();
            }
            return true;
        }
        if (hit.setting() == null) {
            if (button == 1 || hit.dots()) {
                if (!this.expanded.remove(module)) {
                    this.expanded.add(module);
                }
            } else if (button == 0) {
                module.toggle();
                ModuleManager.save();
            }
            return true;
        }
        Setting<?> setting2 = hit.setting();
        if (setting2 instanceof KeybindSetting) {
            KeybindSetting keybind = (KeybindSetting)setting2;
            if (button == 0) {
                this.bindingSetting = keybind;
            } else if (button == 1) {
                keybind.setBind(-1);
                ModuleManager.save();
            }
            return true;
        }
        if (setting2 instanceof ButtonSetting) {
            if (button == 0) {
                ((ButtonSetting)setting2).press();
            }
            return true;
        }
        if (setting2 instanceof ModeSetting) {
            ModeSetting mode = (ModeSetting)setting2;
            mode.setIndex(hit.option());
            ModuleManager.save();
        } else if (button == 0) {
            if (setting2 instanceof BooleanSetting) {
                BooleanSetting bool = (BooleanSetting)setting2;
                bool.toggle();
                ModuleManager.save();
            } else {
                this.dragging = hit;
                if (setting2 instanceof RangeSetting) {
                    double toHigh;
                    RangeSetting range = (RangeSetting)setting2;
                    double progress = (px - (double)hit.trackX()) / (double)Math.max(1, hit.trackW());
                    double toLow = Math.abs(progress - range.lowProgress());
                    this.draggingHandle = toLow < (toHigh = Math.abs(progress - range.highProgress())) || toLow == toHigh && progress < range.lowProgress() ? 0 : 1;
                }
                this.applyDrag(px);
            }
        }
        return true;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.pickerDrag != 0) {
            this.applyPickerDrag(this.mousePixelX(), this.mousePixelY());
            return true;
        }
        if (this.dragging != null) {
            this.applyDrag(this.mousePixelX());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.pickerDrag != 0) {
            this.pickerDrag = 0;
            ModuleManager.save();
            return true;
        }
        if (this.dragging != null) {
            this.dragging = null;
            ModuleManager.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    private void applyDrag(double px) {
        double progress = Math.max(0.0, Math.min(1.0, (px - (double)this.dragging.trackX()) / (double)Math.max(1, this.dragging.trackW())));
        Setting<?> setting = this.dragging.setting();
        if (setting instanceof NumberSetting) {
            NumberSetting number = (NumberSetting)setting;
            number.setProgress(progress);
        } else {
            setting = this.dragging.setting();
            if (setting instanceof ColorSetting) {
                ColorSetting color = (ColorSetting)setting;
                color.setProgress(progress);
            } else {
                setting = this.dragging.setting();
                if (setting instanceof RangeSetting) {
                    RangeSetting range = (RangeSetting)setting;
                    range.setHandleProgress(this.draggingHandle, progress);
                }
            }
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double px = this.mousePixelX();
        double py = this.mousePixelY();
        for (PanelState panel : this.panels.values()) {
            if (!(px >= (double)panel.x) || !(px < (double)(panel.x + panel.width)) || !(py >= (double)panel.listTop) || !(py < (double)panel.listBottom) || panel.maxScroll <= 0) continue;
            int stepPx = Math.max(1, this.minecraft.getWindow().getHeight() / 1080) * 44;
            panel.scrollTarget = (float)Math.max(0.0, Math.min((double)panel.maxScroll, (double)panel.scrollTarget - scrollY * (double)stepPx));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public boolean charTyped(CharacterEvent event) {
        if (this.bindingModule != null || this.bindingSetting != null || this.swallowChar) {
            this.swallowChar = false;
            return true;
        }
        if (this.hexEdit != null) {
            String typed = event.codepointAsString().toUpperCase(Locale.ROOT);
            if (typed.length() == 1 && "0123456789ABCDEF".contains(typed) && this.hexEdit.length() < 6) {
                this.hexEdit = this.hexEdit + typed;
            }
            return true;
        }
        if (event.isAllowedChatCharacter() && this.search.length() < 32) {
            this.searchFocused = true;
            this.setSearch(this.search + event.codepointAsString());
            return true;
        }
        return super.charTyped(event);
    }

    public boolean keyPressed(KeyEvent event) {
        if (this.bindingModule != null) {
            int key = event.key();
            if (!event.isEscape() && key != -1) {
                boolean clear = key == 259 || key == 261;
                this.bindingModule.setBind(clear ? -1 : key);
                ModuleManager.save();
            }
            this.bindingModule = null;
            this.swallowChar = true;
            return true;
        }
        if (this.bindingSetting != null) {
            int key = event.key();
            if (!event.isEscape() && key != -1) {
                boolean clear = key == 259 || key == 261;
                this.bindingSetting.setBind(clear ? -1 : key);
                ModuleManager.save();
            }
            this.bindingSetting = null;
            this.swallowChar = true;
            return true;
        }
        if (this.hexEdit != null) {
            int key = event.key();
            if (key == 259) {
                this.hexEdit = this.hexEdit.isEmpty() ? this.hexEdit : this.hexEdit.substring(0, this.hexEdit.length() - 1);
            } else if (key == 257 || key == 335) {
                this.applyHex();
            } else if (event.isEscape()) {
                this.hexEdit = null;
            }
            return true;
        }
        if (this.picker != null && event.isEscape()) {
            this.closePicker();
            return true;
        }
        if (event.key() == 259 && !this.search.isEmpty()) {
            this.setSearch(event.hasControlDown() ? "" : this.search.substring(0, this.search.length() - 1));
            return true;
        }
        if (event.isEscape() && !this.search.isEmpty()) {
            this.setSearch("");
            this.searchFocused = false;
            return true;
        }
        if (VisualModClient.openGuiKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    public void removed() {
        this.closePicker();
        if (this.dragging != null) {
            this.dragging = null;
            ModuleManager.save();
        }
        super.removed();
    }

    public boolean isPauseScreen() {
        return false;
    }

    public void expand(Module module) {
        this.expanded.add(module);
    }

    public void scrollToEnd() {
        for (PanelState panel : this.panels.values()) {
            panel.scrollTarget = Float.MAX_VALUE;
        }
    }

    public void typeSearch(String text) {
        this.searchFocused = true;
        this.setSearch(text);
    }

    public void startBinding(Module module) {
        this.bindingModule = module;
    }

    private static final class PanelState {
        float scroll;
        float scrollTarget;
        int maxScroll;
        int x;
        int listTop;
        int listBottom;
        int width;

        private PanelState() {
        }
    }

    private record Hit(int x, int y, int w, int h, Module module, Setting<?> setting, int trackX, int trackW, int option, boolean dots) {
        boolean contains(double px, double py) {
            return px >= (double)this.x && px < (double)(this.x + this.w) && py >= (double)this.y && py < (double)(this.y + this.h);
        }
    }
}

