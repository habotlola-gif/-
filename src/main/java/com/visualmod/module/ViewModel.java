package com.visualmod.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.world.entity.HumanoidArm;

public class ViewModel
extends Module {
    public static final double MIN_SIZE = 30.0;
    public static final double MAX_SIZE = 200.0;
    public final BooleanSetting chatEditor = this.setting(new BooleanSetting("chat_editor", "\u0414\u0432\u0438\u0433\u0430\u0442\u044c \u0432 \u0447\u0430\u0442\u0435", true));
    public final NumberSetting rightX = this.setting(new NumberSetting("right_x", "\u041f\u0440\u0430\u0432\u0430\u044f X", 0.0, -1.0, 1.0, 0.01, ""));
    public final NumberSetting rightY = this.setting(new NumberSetting("right_y", "\u041f\u0440\u0430\u0432\u0430\u044f Y", 0.0, -1.0, 1.0, 0.01, ""));
    public final NumberSetting rightZ = this.setting(new NumberSetting("right_z", "\u041f\u0440\u0430\u0432\u0430\u044f Z", 0.0, -1.0, 1.0, 0.01, ""));
    public final NumberSetting rightSize = this.setting(new NumberSetting("right_size", "\u041f\u0440\u0430\u0432\u0430\u044f \u0440\u0430\u0437\u043c\u0435\u0440", 100.0, 30.0, 200.0, 1.0, "%"));
    public final NumberSetting leftX = this.setting(new NumberSetting("left_x", "\u041b\u0435\u0432\u0430\u044f X", 0.0, -1.0, 1.0, 0.01, ""));
    public final NumberSetting leftY = this.setting(new NumberSetting("left_y", "\u041b\u0435\u0432\u0430\u044f Y", 0.0, -1.0, 1.0, 0.01, ""));
    public final NumberSetting leftZ = this.setting(new NumberSetting("left_z", "\u041b\u0435\u0432\u0430\u044f Z", 0.0, -1.0, 1.0, 0.01, ""));
    public final NumberSetting leftSize = this.setting(new NumberSetting("left_size", "\u041b\u0435\u0432\u0430\u044f \u0440\u0430\u0437\u043c\u0435\u0440", 100.0, 30.0, 200.0, 1.0, "%"));

    public ViewModel() {
        super("ViewModel", "\u041f\u043e\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u0440\u0443\u043a \u2014 \u0434\u0432\u0438\u0433\u0430\u0439 \u043c\u044b\u0448\u044c\u044e \u0432 \u0447\u0430\u0442\u0435", Category.VISUALS);
        this.setEnabled(true);
    }

    public NumberSetting x(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? this.rightX : this.leftX;
    }

    public NumberSetting y(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? this.rightY : this.leftY;
    }

    public NumberSetting z(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? this.rightZ : this.leftZ;
    }

    public NumberSetting size(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? this.rightSize : this.leftSize;
    }

    public boolean isChatEditorActive() {
        return this.isEnabled() && this.chatEditor.isOn();
    }

    public void reset(HumanoidArm arm) {
        this.x(arm).set(0.0);
        this.y(arm).set(0.0);
        this.z(arm).set(0.0);
        this.size(arm).set(100.0);
    }

    public void apply(PoseStack pose, HumanoidArm arm) {
        if (!this.isEnabled()) {
            return;
        }
        pose.translate(this.x(arm).getFloat(), this.y(arm).getFloat(), this.z(arm).getFloat());
        float s = this.size(arm).getFloat() / 100.0f;
        if (s != 1.0f) {
            pose.scale(s, s, s);
        }
    }
}

