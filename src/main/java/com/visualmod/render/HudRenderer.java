package com.visualmod.render;

import com.visualmod.FriendManager;
import com.visualmod.gui.UiRenderer;
import com.visualmod.module.Hud;
import com.visualmod.module.Keybind;
import com.visualmod.module.Module;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.TargetTracker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

public final class HudRenderer {
    public static final int ELEMENT_KEYBINDS = 0;
    public static final int ELEMENT_TARGET = 1;
    private static final int COLOR_PANEL = -435483889;
    private static final int COLOR_BORDER = -14474453;
    private static final int COLOR_BORDER_EDIT = -9803144;
    private static final int COLOR_TEXT = -1;
    private static final int COLOR_TEXT_OFF = -7566184;
    private static final int COLOR_FAINT = -10724248;
    private static final int COLOR_PILL = -14935005;
    private static final int COLOR_BAR_BACK = -14803419;
    private static final int COLOR_LOW = -1096636;
    private static final int COLOR_MID = -340971;
    private static final int COLOR_HIGH = -11870592;
    private static final int COLOR_FRIEND = -11870592;
    private static final int KB_PANEL = -669904352;
    private static final int KB_ACCENT = -11571465;
    private static final int KB_ACCENT_OFF = -12958864;
    private static final int[][] RECTS = new int[2][];
    private static final Map<Module, Float> enabledProgress = new HashMap<Module, Float>();
    private static float targetAlpha;
    private static float shownHealth;
    private static @Nullable LivingEntity shownTarget;
    private static long lastFrame;

    private HudRenderer() {
    }

    public static int @Nullable [] rect(int element) {
        return RECTS[element];
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Hud hud = ModuleManager.HUD;
        Minecraft client = Minecraft.getInstance();
        HudRenderer.RECTS[0] = null;
        HudRenderer.RECTS[1] = null;
        if (!hud.isEnabled() || client.player == null || client.options.hideGui) {
            return;
        }
        long now = System.nanoTime();
        float seconds = Math.min((float)(now - lastFrame) / 1.0E9f, 0.1f);
        lastFrame = now;
        float step = 1.0f - (float)Math.exp(-seconds * 14.0f);
        boolean editing = client.screen instanceof ChatScreen;
        UiRenderer ui = UiRenderer.begin(graphics);
        try {
            int screenW = client.getWindow().getWidth();
            int screenH = client.getWindow().getHeight();
            if (hud.keybinds.isOn()) {
                HudRenderer.drawKeybinds(ui, hud, screenW, screenH, step, editing);
            }
            if (hud.targetHud.isOn()) {
                HudRenderer.drawTarget(ui, client, hud, screenW, screenH, step, editing);
            }
        }
        finally {
            ui.end();
        }
    }

    private static float approach(float current, float target, float step) {
        float next = current + (target - current) * step;
        return Math.abs(target - next) < 0.003f ? target : next;
    }

    private static int clampX(int x, int w, int screenW) {
        return Math.max(0, Math.min(screenW - w, x));
    }

    private static void drawKeybinds(UiRenderer ui, Hud hud, int screenW, int screenH, float step, boolean editing) {
        ArrayList<Module> bound = new ArrayList<Module>();
        for (Module module : ModuleManager.MODULES) {
            float target = module.isEnabled() ? 1.0f : 0.0f;
            enabledProgress.put(module, Float.valueOf(HudRenderer.approach(enabledProgress.getOrDefault(module, Float.valueOf(target)).floatValue(), target, step)));
            if (module.getBind() == -1 || hud.onlyEnabled.isOn() && !module.isEnabled()) continue;
            bound.add(module);
        }
        if (bound.isEmpty() && !editing) {
            return;
        }
        int padX = ui.dp(12.0f);
        int headerH = ui.dp(28.0f);
        int rowH = ui.dp(21.0f);
        int gap = ui.dp(16.0f);
        int icon = ui.dp(12.0f);
        int contentW = ui.textWidth(UiRenderer.TextStyle.BODY, "Keybinds") + gap + icon;
        for (Module module : bound) {
            contentW = Math.max(contentW, ui.textWidth(UiRenderer.TextStyle.SMALL, module.getName()) + gap + ui.textWidth(UiRenderer.TextStyle.SMALL, HudRenderer.keyLabel(module)));
        }
        int w = Math.max(ui.dp(132.0f), contentW + padX * 2);
        int listH = bound.isEmpty() ? rowH : bound.size() * rowH;
        int h = headerH + listH + ui.dp(6.0f);
        int x = HudRenderer.clampX((int)Math.round(hud.keybindsX.get() * (double)screenW), w, screenW);
        int y = Math.max(0, Math.min(screenH - h, (int)Math.round(hud.keybindsY.get() * (double)screenH)));
        HudRenderer.RECTS[0] = new int[]{x, y, w, h};
        ui.shadow(x, y, w, h, ui.dp(9.0f), ui.dp(16.0f), 0x60000000);
        if (editing) {
            ui.roundRectBordered(x, y, w, h, ui.dp(9.0f), Math.max(1, ui.dp(1.0f)), -9803144, -669904352);
        } else {
            ui.roundRect(x, y, w, h, ui.dp(9.0f), -669904352);
        }
        ui.textCentered(UiRenderer.TextStyle.BODY, "Keybinds", x + padX, y + ui.dp(2.0f), headerH, -1);
        int iconX = x + w - padX - icon;
        int iconY = y + ui.dp(2.0f) + (headerH - icon) / 2;
        ui.roundRect(iconX, iconY, icon, icon, ui.dp(3.0f), -11571465);
        int line = Math.max(1, ui.dp(1.5f));
        int lineW = icon - ui.dp(5.0f);
        ui.roundRect(iconX + (icon - lineW) / 2, iconY + icon / 2 - line - ui.dp(1.0f), lineW, line, line / 2, -788529153);
        ui.roundRect(iconX + (icon - lineW) / 2, iconY + icon / 2 + ui.dp(1.0f), lineW, line, line / 2, -788529153);
        int rowY = y + headerH;
        if (bound.isEmpty()) {
            ui.textCentered(UiRenderer.TextStyle.SMALL, "\u041d\u0435\u0442 \u0431\u0438\u043d\u0434\u043e\u0432", x + padX, rowY, rowH, -10724248);
            return;
        }
        for (Module module : bound) {
            float on = enabledProgress.get(module).floatValue();
            ui.textCentered(UiRenderer.TextStyle.SMALL, module.getName(), x + padX, rowY, rowH, UiRenderer.lerpColor(-7566184, -1, on));
            String key = HudRenderer.keyLabel(module);
            ui.textCentered(UiRenderer.TextStyle.SMALL, key, x + w - padX - ui.textWidth(UiRenderer.TextStyle.SMALL, key), rowY, rowH, UiRenderer.lerpColor(-12958864, -11571465, on));
            rowY += rowH;
        }
    }

    private static String keyLabel(Module module) {
        return Keybind.displayName(module.getBind()).toUpperCase(Locale.ROOT);
    }

    private static void drawTarget(UiRenderer ui, Minecraft client, Hud hud, int screenW, int screenH, float step, boolean editing) {
        LivingEntity target = TargetTracker.current();
        if (target == null && editing) {
            target = client.player;
        }
        if (target != null) {
            if (target != shownTarget) {
                shownHealth = -1.0f;
            }
            shownTarget = target;
        }
        if ((targetAlpha = HudRenderer.approach(targetAlpha, target != null ? 1.0f : 0.0f, step)) <= 0.01f || shownTarget == null) {
            shownTarget = targetAlpha <= 0.01f ? null : shownTarget;
            return;
        }
        LivingEntity entity = shownTarget;
        int w = ui.dp(200.0f);
        int h = ui.dp(62.0f);
        int pad = ui.dp(10.0f);
        int x = HudRenderer.clampX((int)Math.round(hud.targetX.get() * (double)screenW), w, screenW);
        int y = Math.max(0, Math.min(screenH - h, (int)Math.round(hud.targetY.get() * (double)screenH)));
        HudRenderer.RECTS[1] = new int[]{x, y, w, h};
        ui.setOpacity(targetAlpha);
        HudRenderer.panel(ui, x, y += Math.round((1.0f - targetAlpha) * (float)ui.dp(8.0f)), w, h, editing);
        int face = h - pad * 2;
        int faceX = x + pad;
        int faceY = y + pad;
        ui.roundRect(faceX - 1, faceY - 1, face + 2, face + 2, ui.dp(7.0f), -14935005);
        if (entity instanceof AbstractClientPlayer) {
            AbstractClientPlayer player = (AbstractClientPlayer)entity;
            PlayerFaceRenderer.draw((GuiGraphics)ui.graphics(), (PlayerSkin)player.getSkin(), (int)faceX, (int)faceY, (int)face);
        } else {
            ui.roundRect(faceX, faceY, face, face, ui.dp(6.0f), HudRenderer.accent(0.3f));
            String letter = entity.getName().getString().substring(0, 1).toUpperCase(Locale.ROOT);
            int lw = ui.textWidth(UiRenderer.TextStyle.TITLE, letter);
            ui.textCentered(UiRenderer.TextStyle.TITLE, letter, faceX + (face - lw) / 2, faceY, face, -1);
        }
        if (entity.hurtTime > 0) {
            int flash = Math.round((float)(112 * entity.hurtTime) / 10.0f);
            ui.roundRect(faceX, faceY, face, face, ui.dp(6.0f), flash << 24 | 0xEF4444);
        }
        int textX = faceX + face + ui.dp(10.0f);
        int textW = x + w - pad - textX;
        String name = entity.getName().getString();
        boolean friend = entity instanceof AbstractClientPlayer && FriendManager.isFriend(name);
        ui.pushClip(textX, y, textW, h);
        ui.textCentered(UiRenderer.TextStyle.NAME, name, textX, faceY - ui.dp(1.0f), ui.dp(18.0f), friend ? -11870592 : -1);
        ui.popClip();
        float maxHealth = Math.max(1.0f, entity.getMaxHealth());
        float health = Math.max(0.0f, entity.getHealth());
        float absorption = entity.getAbsorptionAmount();
        shownHealth = shownHealth < 0.0f ? health : HudRenderer.approach(shownHealth, health, step * 0.6f);
        float fraction = Math.min(1.0f, shownHealth / maxHealth);
        int healthColor = fraction > 0.5f ? UiRenderer.lerpColor(-340971, -11870592, (fraction - 0.5f) * 2.0f) : UiRenderer.lerpColor(-1096636, -340971, fraction * 2.0f);
        Object info = String.format(Locale.ROOT, "%.1f HP", Float.valueOf(health + absorption));
        if (friend) {
            info = (String)info + "  \u00b7  \u0434\u0440\u0443\u0433";
        }
        String distance = (double)Math.round(client.player.distanceTo((Entity)entity) * 10.0f) / 10.0 + "\u043c";
        int infoY = faceY + ui.dp(17.0f);
        ui.textCentered(UiRenderer.TextStyle.SMALL, (String)info, textX, infoY, ui.dp(14.0f), -7566184);
        ui.textCentered(UiRenderer.TextStyle.SMALL, distance, x + w - pad - ui.textWidth(UiRenderer.TextStyle.SMALL, distance), infoY, ui.dp(14.0f), -10724248);
        int barH = ui.dp(6.0f);
        int barY = faceY + face - barH;
        ui.roundRect(textX, barY, textW, barH, barH / 2, -14803419);
        ui.roundRect(textX, barY, Math.max(barH, Math.round((float)textW * fraction)), barH, barH / 2, healthColor);
        if (absorption > 0.0f) {
            int absW = Math.round((float)textW * Math.min(1.0f, absorption / maxHealth));
            ui.roundRect(textX, barY, Math.max(barH, absW), barH / 2, barH / 4, -11443);
        }
        ui.setOpacity(1.0f);
    }

    private static void panel(UiRenderer ui, int x, int y, int w, int h, boolean editing) {
        ui.shadow(x, y, w, h, ui.dp(10.0f), ui.dp(14.0f), 0x70000000);
        ui.roundRectBordered(x, y, w, h, ui.dp(10.0f), Math.max(1, ui.dp(1.0f)), editing ? -9803144 : -14474453, -435483889);
    }

    private static int accent(float offset) {
        float t = 0.5f + 0.5f * (float)Math.sin((double)System.nanoTime() / 1.0E9 * 1.2 + (double)offset * 6.0);
        return UiRenderer.lerpColor(ModuleManager.SHADER_HANDS.colorA.rgb(), ModuleManager.SHADER_HANDS.colorB.rgb(), t);
    }

    static {
        shownHealth = -1.0f;
        lastFrame = System.nanoTime();
    }
}

