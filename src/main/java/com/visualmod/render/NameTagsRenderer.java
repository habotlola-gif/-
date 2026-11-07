package com.visualmod.render;

import com.visualmod.FriendManager;
import com.visualmod.gui.UiRenderer;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.NameTags;
import com.visualmod.render.WorldProjection;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class NameTagsRenderer {
    private static final EquipmentSlot[] ITEM_ORDER = new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
    private static final int COLOR_BACKGROUND = -805306368;
    private static final int COLOR_BORDER = -14935012;
    private static final int COLOR_NAME = -1;
    private static final int COLOR_FRIEND_NAME = -11870592;
    private static final int COLOR_FRIEND_BORDER = -14714299;
    private static final int COLOR_FRIEND_BACKGROUND = -805037556;
    private static final int COLOR_FRIEND_GLOW = 1428342110;
    private static final int COLOR_MUTED = -7697782;
    private static final int COLOR_BAR_BACK = -14803426;
    private static final int COLOR_LOW = -1096636;
    private static final int COLOR_MID = -340971;
    private static final int COLOR_HIGH = -11870592;

    private NameTagsRenderer() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        NameTags settings = ModuleManager.NAME_TAGS;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!settings.isEnabled() || client.level == null || player == null || client.options.hideGui) {
            return;
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        double range = settings.range.get();
        ArrayList<Tag> tags = new ArrayList<Tag>();
        for (Entity entity : client.level.entitiesForRendering()) {
            Vec3 anchor;
            float[] screen;
            double distance;
            LivingEntity living;
            if (!settings.shouldTag(entity) || !(entity instanceof LivingEntity) || !(living = (LivingEntity)entity).isAlive() || (distance = (double)player.distanceTo((Entity)living)) > range || !settings.throughWalls.isOn() && !player.hasLineOfSight((Entity)living) || (screen = WorldProjection.toScreen(anchor = living.getPosition(partialTick).add(0.0, (double)living.getBbHeight() + 0.3, 0.0))) == null) continue;
            tags.add(new Tag(living, distance, screen[0], screen[1]));
        }
        if (tags.isEmpty()) {
            return;
        }
        tags.sort(Comparator.comparingDouble(Tag::distance).reversed());
        float scale = settings.scale.getFloat() / 100.0f;
        float wanted = UiRenderer.densityFor((float)client.getWindow().getHeight() / 1080.0f) * scale;
        UiRenderer ui = UiRenderer.begin(graphics, Math.max(1.0f, scale));
        float residual = wanted / ui.density();
        try {
            for (Tag tag : tags) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(tag.x(), tag.y());
                graphics.pose().scale(residual, residual);
                graphics.pose().translate(-tag.x(), -tag.y());
                NameTagsRenderer.drawTag(ui, settings, tag);
                graphics.pose().popMatrix();
            }
        }
        finally {
            ui.end();
        }
    }

    /** Плашка: тёмная скруглённая карточка, слева голова игрока, ник и здоровье в скобках цветом по HP. */
    private static void drawCard(UiRenderer ui, NameTags settings, Tag tag) {
        LivingEntity entity = tag.entity();
        String name = entity.getName().getString();
        float maxHealth = Math.max(1.0f, entity.getMaxHealth());
        float fraction = Math.max(0.0f, Math.min(1.0f, entity.getHealth() / maxHealth));
        int healthColor = fraction > 0.5f ? UiRenderer.lerpColor(-340971, -11870592, (fraction - 0.5f) * 2.0f) : UiRenderer.lerpColor(-1096636, -340971, fraction * 2.0f);
        String healthText = "[" + NameTagsRenderer.formatHealth(entity.getHealth() + entity.getAbsorptionAmount()) + "]";
        String distanceText = Math.round(tag.distance()) + "\u043c";
        boolean friend = entity instanceof Player && FriendManager.isFriend(name);

        int height = ui.dp(28.0f);
        int radius = ui.dp(9.0f);
        int padL = ui.dp(5.0f);
        int padR = ui.dp(10.0f);
        int gap = ui.dp(6.0f);
        int face = ui.dp(18.0f);
        boolean hasFace = entity instanceof AbstractClientPlayer;

        int width = padL + padR + ui.textWidth(UiRenderer.TextStyle.BODY, name);
        if (hasFace) {
            width += face + gap;
        } else {
            width += ui.dp(5.0f);
        }
        if (settings.health.isOn()) {
            width += gap + ui.textWidth(UiRenderer.TextStyle.BODY, healthText);
        }
        if (settings.distance.isOn()) {
            width += gap + ui.textWidth(UiRenderer.TextStyle.SMALL, distanceText);
        }

        int x = Math.round(tag.x() - (float)width / 2.0f);
        int y = Math.round(tag.y()) - height;
        ui.shadow(x, y, width, height, radius, ui.dp(10.0f), friend ? 1428342110 : 0x66000000);
        ui.roundRectBordered(x, y, width, height, radius, Math.max(1, ui.dp(1.0f)), friend ? -14714299 : 0xFF2A2D35, friend ? -805037556 : 0xE0101216);

        int cursor = x + padL;
        if (hasFace) {
            int fy = y + (height - face) / 2;
            ui.roundRect(cursor - 1, fy - 1, face + 2, face + 2, ui.dp(6.0f), 0xFF2F333C);
            PlayerFaceRenderer.draw(ui.graphics(), ((AbstractClientPlayer)entity).getSkin(), cursor, fy, face);
            cursor += face + gap;
        } else {
            cursor += ui.dp(5.0f);
        }
        ui.textCentered(UiRenderer.TextStyle.BODY, name, cursor, y, height, friend ? -11870592 : -1);
        cursor += ui.textWidth(UiRenderer.TextStyle.BODY, name);
        if (settings.health.isOn()) {
            cursor += gap;
            ui.textCentered(UiRenderer.TextStyle.BODY, healthText, cursor, y, height, healthColor);
            cursor += ui.textWidth(UiRenderer.TextStyle.BODY, healthText);
        }
        if (settings.distance.isOn()) {
            cursor += gap;
            ui.textCentered(UiRenderer.TextStyle.SMALL, distanceText, cursor, y, height, -7697782);
        }
        if (settings.items.isOn()) {
            NameTagsRenderer.drawItems(ui, entity, x + width / 2, y - ui.dp(3.0f));
        }
    }

    private static void drawTag(UiRenderer ui, NameTags settings, Tag tag) {
        if (settings.isCard()) {
            NameTagsRenderer.drawCard(ui, settings, tag);
            return;
        }
        boolean friend;
        LivingEntity entity = tag.entity();
        String name = entity.getName().getString();
        float maxHealth = Math.max(1.0f, entity.getMaxHealth());
        float health = entity.getHealth() + entity.getAbsorptionAmount();
        float fraction = Math.max(0.0f, Math.min(1.0f, entity.getHealth() / maxHealth));
        int healthColor = fraction > 0.5f ? UiRenderer.lerpColor(-340971, -11870592, (fraction - 0.5f) * 2.0f) : UiRenderer.lerpColor(-1096636, -340971, fraction * 2.0f);
        String healthText = NameTagsRenderer.formatHealth(health);
        String distanceText = Math.round(tag.distance()) + "\u043c";
        int padX = ui.dp(9.0f);
        int gap = ui.dp(8.0f);
        int height = ui.dp(24.0f);
        int dot = ui.dp(6.0f);
        int width = padX * 2 + ui.textWidth(UiRenderer.TextStyle.BODY, name);
        if (settings.health.isOn()) {
            width += gap + dot + ui.dp(4.0f) + ui.textWidth(UiRenderer.TextStyle.SMALL, healthText);
        }
        if (settings.distance.isOn()) {
            width += gap + ui.textWidth(UiRenderer.TextStyle.SMALL, distanceText);
        }
        int x = Math.round(tag.x() - (float)width / 2.0f);
        int y = Math.round(tag.y()) - height;
        boolean bl = friend = entity instanceof Player && FriendManager.isFriend(name);
        if (friend) {
            ui.shadow(x, y, width, height, height / 2, ui.dp(10.0f), 1428342110);
        }
        ui.roundRectBordered(x, y, width, height, height / 2, Math.max(1, ui.dp(1.0f)), friend ? -14714299 : -14935012, friend ? -805037556 : -805306368);
        int cursor = x + padX;
        ui.textCentered(UiRenderer.TextStyle.BODY, name, cursor, y, height, friend ? -11870592 : -1);
        cursor += ui.textWidth(UiRenderer.TextStyle.BODY, name);
        if (settings.health.isOn()) {
            ui.roundRect(cursor += gap, y + (height - dot) / 2, dot, dot, dot / 2, healthColor);
            ui.textCentered(UiRenderer.TextStyle.SMALL, healthText, cursor += dot + ui.dp(4.0f), y, height, healthColor);
            cursor += ui.textWidth(UiRenderer.TextStyle.SMALL, healthText);
            int barInset = height / 2;
            int barW = width - barInset * 2;
            int barH = Math.max(1, ui.dp(2.0f));
            int barY = y + height - barH - Math.max(1, ui.dp(2.0f));
            ui.roundRect(x + barInset, barY, barW, barH, barH / 2, -14803426);
            ui.roundRect(x + barInset, barY, Math.max(barH, Math.round((float)barW * fraction)), barH, barH / 2, healthColor);
        }
        if (settings.distance.isOn()) {
            ui.textCentered(UiRenderer.TextStyle.SMALL, distanceText, cursor += gap, y, height, -7697782);
        }
        if (settings.items.isOn()) {
            NameTagsRenderer.drawItems(ui, entity, x + width / 2, y - ui.dp(3.0f));
        }
    }

    private static void drawItems(UiRenderer ui, LivingEntity entity, int centerX, int bottom) {
        ArrayList<ItemStack> stacks = new ArrayList<ItemStack>();
        for (EquipmentSlot slot : ITEM_ORDER) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            stacks.add(stack);
        }
        if (stacks.isEmpty()) {
            return;
        }
        int icon = ui.dp(18.0f);
        int spacing = ui.dp(2.0f);
        int rowWidth = stacks.size() * icon + (stacks.size() - 1) * spacing;
        int x = centerX - rowWidth / 2;
        int y = bottom - icon;
        float scale = (float)icon / 16.0f;
        GuiGraphics graphics = ui.graphics();
        for (ItemStack stack : stacks) {
            graphics.pose().pushMatrix();
            graphics.pose().translate((float)x, (float)y);
            graphics.pose().scale(scale, scale);
            graphics.renderItem(stack, 0, 0);
            graphics.pose().popMatrix();
            x += icon + spacing;
        }
    }

    private static String formatHealth(float health) {
        float rounded = (float)Math.round(health * 2.0f) / 2.0f;
        return (double)rounded == Math.floor(rounded) ? Integer.toString((int)rounded) : Float.toString(rounded);
    }

    private record Tag(LivingEntity entity, double distance, float x, float y) {
    }
}

