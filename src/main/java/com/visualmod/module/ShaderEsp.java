package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ColorSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

public class ShaderEsp
extends Module {
    public static final int COLOR_GRADIENT = 0;
    public static final int COLOR_TEAM = 1;
    public final BooleanSetting players = this.setting(new BooleanSetting("players", "\u0418\u0433\u0440\u043e\u043a\u0438", true));
    public final BooleanSetting monsters = this.setting(new BooleanSetting("monsters", "\u041c\u043e\u043d\u0441\u0442\u0440\u044b", true));
    public final BooleanSetting animals = this.setting(new BooleanSetting("animals", "\u0416\u0438\u0432\u043e\u0442\u043d\u044b\u0435", false));
    public final BooleanSetting items = this.setting(new BooleanSetting("items", "\u041f\u0440\u0435\u0434\u043c\u0435\u0442\u044b", false));
    public final ModeSetting colorMode = this.setting(new ModeSetting("color_mode", "\u0426\u0432\u0435\u0442", "\u0413\u0440\u0430\u0434\u0438\u0435\u043d\u0442", "\u0426\u0432\u0435\u0442 \u043a\u043e\u043c\u0430\u043d\u0434\u044b"));
    public final ColorSetting colorA = this.setting(new ColorSetting("color_a", "\u0426\u0432\u0435\u0442 1", 262.0f));
    public final ColorSetting colorB = this.setting(new ColorSetting("color_b", "\u0426\u0432\u0435\u0442 2", 188.0f));
    public final NumberSetting fill = this.setting(new NumberSetting("fill", "\u0417\u0430\u043b\u0438\u0432\u043a\u0430", 25.0, 0.0, 100.0, 5.0, "%"));
    public final NumberSetting glow = this.setting(new NumberSetting("glow", "\u0421\u0432\u0435\u0447\u0435\u043d\u0438\u0435", 10.0, 0.0, 24.0, 1.0, " px"));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "\u0421\u043a\u043e\u0440\u043e\u0441\u0442\u044c", 1.0, 0.0, 3.0, 0.1, "x"));

    public ShaderEsp() {
        super("ShaderESP", "\u041f\u043e\u0434\u0441\u0432\u0435\u0442\u043a\u0430 \u0441\u0443\u0449\u043d\u043e\u0441\u0442\u0435\u0439 \u0441\u043a\u0432\u043e\u0437\u044c \u0441\u0442\u0435\u043d\u044b", Category.VISUALS);
    }

    public boolean shouldHighlight(Entity entity) {
        if (!this.isEnabled() || entity instanceof LocalPlayer) {
            return false;
        }
        if (entity instanceof Player) {
            return this.players.isOn() && !entity.isSpectator();
        }
        if (entity instanceof ItemEntity) {
            return this.items.isOn();
        }
        if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand) {
            return false;
        }
        return entity.getType().getCategory() == MobCategory.MONSTER ? this.monsters.isOn() : this.animals.isOn();
    }
}

