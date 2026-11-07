package com.visualmod.module;

import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

public class NameTags
extends Module {
    public static final String STYLE_CARD = "\u041f\u043b\u0430\u0448\u043a\u0430";
    public final ModeSetting style = this.setting(new ModeSetting("style", "\u0421\u0442\u0438\u043b\u044c", "\u041f\u043b\u0430\u0448\u043a\u0430", "\u041a\u043b\u0430\u0441\u0441\u0438\u043a\u0430"));
    public final BooleanSetting players = this.setting(new BooleanSetting("players", "\u0418\u0433\u0440\u043e\u043a\u0438", true));
    public final BooleanSetting mobs = this.setting(new BooleanSetting("mobs", "\u041c\u043e\u0431\u044b", false));
    public final BooleanSetting health = this.setting(new BooleanSetting("health", "\u0417\u0434\u043e\u0440\u043e\u0432\u044c\u0435", true));
    public final BooleanSetting distance = this.setting(new BooleanSetting("distance", "\u0414\u0438\u0441\u0442\u0430\u043d\u0446\u0438\u044f", true));
    public final BooleanSetting items = this.setting(new BooleanSetting("items", "\u0411\u0440\u043e\u043d\u044f \u0438 \u043f\u0440\u0435\u0434\u043c\u0435\u0442\u044b", true));
    public final BooleanSetting throughWalls = this.setting(new BooleanSetting("through_walls", "\u0421\u043a\u0432\u043e\u0437\u044c \u0441\u0442\u0435\u043d\u044b", true));
    public final NumberSetting scale = this.setting(new NumberSetting("scale", "\u0420\u0430\u0437\u043c\u0435\u0440", 100.0, 40.0, 200.0, 5.0, "%"));
    public final NumberSetting range = this.setting(new NumberSetting("range", "\u0414\u0430\u043b\u044c\u043d\u043e\u0441\u0442\u044c", 128.0, 16.0, 256.0, 8.0, " \u0431\u043b."));

    public NameTags() {
        super("NameTags", "\u0422\u0430\u0431\u043b\u0438\u0447\u043a\u0438 \u0441 \u043d\u0438\u043a\u043e\u043c \u0438 \u0437\u0434\u043e\u0440\u043e\u0432\u044c\u0435\u043c", Category.VISUALS);
    }

    public boolean isCard() {
        return STYLE_CARD.equals(this.style.get());
    }

    public boolean shouldTag(Entity entity) {
        if (!this.isEnabled() || !(entity instanceof LivingEntity) || entity instanceof LocalPlayer || entity instanceof ArmorStand) {
            return false;
        }
        return entity instanceof Player ? this.players.isOn() : this.mobs.isOn();
    }
}

