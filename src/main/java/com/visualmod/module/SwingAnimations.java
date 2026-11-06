package com.visualmod.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.visualmod.module.Category;
import com.visualmod.module.Module;
import com.visualmod.setting.BooleanSetting;
import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.util.Mth;
import org.joml.Quaternionfc;

public class SwingAnimations
extends Module {
    public static final int MODE_VANILLA = 0;
    public static final int MODE_SMOOTH = 1;
    public static final int MODE_BLOCK = 2;
    public static final int MODE_SPIN = 3;
    public static final int MODE_CHOP = 4;
    public static final int MODE_STAB = 5;
    public final ModeSetting mode = this.setting(new ModeSetting("mode", "\u0410\u043d\u0438\u043c\u0430\u0446\u0438\u044f", "\u0412\u0430\u043d\u0438\u043b\u044c\u043d\u0430\u044f", "\u041f\u043b\u0430\u0432\u043d\u0430\u044f", "\u0411\u043b\u043e\u043a 1.7", "\u0412\u0440\u0430\u0449\u0435\u043d\u0438\u0435", "\u0420\u0443\u0431\u044f\u0449\u0430\u044f", "\u0422\u044b\u0447\u043e\u043a"));
    public final NumberSetting speed = this.setting(new NumberSetting("speed", "\u0421\u043a\u043e\u0440\u043e\u0441\u0442\u044c \u0432\u0437\u043c\u0430\u0445\u0430", 100.0, 50.0, 200.0, 5.0, "%"));
    public final BooleanSetting noEquip = this.setting(new BooleanSetting("no_equip", "\u0411\u0435\u0437 \u043e\u043f\u0443\u0441\u043a\u0430\u043d\u0438\u044f \u043f\u0440\u0438 \u0441\u043c\u0435\u043d\u0435", false));

    public SwingAnimations() {
        super("SwingAnimations", "\u0410\u043d\u0438\u043c\u0430\u0446\u0438\u0438 \u0432\u0437\u043c\u0430\u0445\u0430 \u0438 \u0441\u043a\u043e\u0440\u043e\u0441\u0442\u044c", Category.VISUALS);
        this.mode.setIndex(4);
    }

    public int adjustDuration(int duration) {
        if (!this.isEnabled()) {
            return duration;
        }
        return Math.max(1, Math.round((float)duration * 100.0f / this.speed.getFloat()));
    }

    public boolean replacesSwing() {
        return this.isEnabled() && this.mode.getIndex() != 0;
    }

    public void applySwing(PoseStack pose, float progress, int side) {
        float arc = Mth.sin((double)(progress * (float)Math.PI));
        float snap = Mth.sin((double)(Mth.sqrt((float)progress) * (float)Math.PI));
        float eased = progress * progress * (3.0f - 2.0f * progress);
        switch (this.mode.getIndex()) {
            case 1: {
                pose.translate((float)side * -0.12f * arc, 0.04f * arc, -0.12f * arc);
                pose.mulPose((Quaternionfc)Axis.YP.rotationDegrees((float)side * -12.0f * arc));
                pose.mulPose((Quaternionfc)Axis.XP.rotationDegrees(-55.0f * arc));
                break;
            }
            case 2: {
                pose.translate((float)side * -0.14f * snap, 0.08f * snap, 0.14f * snap);
                pose.mulPose((Quaternionfc)Axis.XP.rotationDegrees(-102.25f * snap));
                pose.mulPose((Quaternionfc)Axis.YP.rotationDegrees((float)side * 13.365f * snap));
                pose.mulPose((Quaternionfc)Axis.ZP.rotationDegrees((float)side * 78.05f * snap));
                pose.mulPose((Quaternionfc)Axis.XP.rotationDegrees(-25.0f * arc));
                break;
            }
            case 3: {
                pose.translate((float)side * -0.1f * arc, 0.05f * arc, -0.1f * arc);
                pose.mulPose((Quaternionfc)Axis.YP.rotationDegrees((float)side * 360.0f * eased));
                break;
            }
            case 4: {
                float angle;
                if (progress < 0.25f) {
                    float t = progress / 0.25f;
                    angle = 35.0f * t * (2.0f - t);
                } else if (progress < 0.55f) {
                    float t = (progress - 0.25f) / 0.3f;
                    angle = Mth.lerp((float)(t * t), (float)35.0f, (float)-60.0f);
                } else {
                    float t = (progress - 0.55f) / 0.45f;
                    angle = -60.0f * (1.0f - t * t * (3.0f - 2.0f * t));
                }
                pose.translate((float)side * -0.06f * arc, 0.08f * arc, -0.16f * arc);
                pose.mulPose((Quaternionfc)Axis.ZP.rotationDegrees((float)side * 12.0f * arc));
                pose.mulPose((Quaternionfc)Axis.XP.rotationDegrees(angle));
                break;
            }
            case 5: {
                pose.translate((float)side * -0.05f * arc, 0.02f * arc, -0.45f * arc);
                pose.mulPose((Quaternionfc)Axis.XP.rotationDegrees(-10.0f * arc));
                break;
            }
        }
    }
}

