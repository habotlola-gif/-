package com.visualmod.mixin;

import com.visualmod.gui.UiRenderer;
import com.visualmod.module.Hud;
import com.visualmod.module.ModuleManager;
import com.visualmod.module.ViewModel;
import com.visualmod.render.HudRenderer;
import java.util.Locale;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={ChatScreen.class})
public abstract class ChatScreenMixin
extends Screen {
    @Unique
    private static final float HAND_DEPTH = 0.72f;
    @Unique
    private static final double SIZE_STEP = 5.0;
    @Unique
    private @Nullable HumanoidArm visualmod$draggedArm;
    @Unique
    private int visualmod$hudElement = -1;
    @Unique
    private double visualmod$hudGrabX;
    @Unique
    private double visualmod$hudGrabY;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private HumanoidArm visualmod$armAt(double guiX) {
        return guiX < (double)this.width / 2.0 ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    @Unique
    private boolean visualmod$overChatMessages(double guiX, double guiY) {
        Options options = this.minecraft.options;
        double scale = (Double)options.chatScale().get();
        double chatWidth = (double)ChatComponent.getWidth((double)((Double)options.chatWidth().get())) * scale + 12.0;
        double chatHeight = (double)ChatComponent.getHeight((double)((Double)options.chatHeightFocused().get())) * scale;
        double chatBottom = this.height - 40;
        return guiX < chatWidth && guiY > chatBottom - chatHeight && guiY < chatBottom + 26.0;
    }

    @Inject(method={"mouseClicked"}, at={@At(value="RETURN")}, cancellable=true)
    private void visualmod$startHandDrag(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        ViewModel viewModel;
        if (cir.getReturnValueZ()) {
            return;
        }
        if (event.button() == 0 && ModuleManager.HUD.isEnabled()) {
            int scale = this.minecraft.getWindow().getGuiScale();
            double px = event.x() * (double)scale;
            double py = event.y() * (double)scale;
            for (int element = 0; element < 2; ++element) {
                int[] r = HudRenderer.rect(element);
                if (r == null || !(px >= (double)r[0]) || !(px < (double)(r[0] + r[2])) || !(py >= (double)r[1]) || !(py < (double)(r[1] + r[3]))) continue;
                this.visualmod$hudElement = element;
                this.visualmod$hudGrabX = px - (double)r[0];
                this.visualmod$hudGrabY = py - (double)r[1];
                cir.setReturnValue(true);
                return;
            }
        }
        if (!(viewModel = ModuleManager.VIEW_MODEL).isChatEditorActive()) {
            return;
        }
        HumanoidArm arm = this.visualmod$armAt(event.x());
        if (event.button() == 0) {
            this.visualmod$draggedArm = arm;
            cir.setReturnValue(true);
        } else if (event.button() == 1) {
            viewModel.reset(arm);
            ModuleManager.save();
            cir.setReturnValue(true);
        }
    }

    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.visualmod$hudElement >= 0) {
            int scale = this.minecraft.getWindow().getGuiScale();
            double screenW = this.minecraft.getWindow().getWidth();
            double screenH = this.minecraft.getWindow().getHeight();
            double fx = (event.x() * (double)scale - this.visualmod$hudGrabX) / screenW;
            double fy = (event.y() * (double)scale - this.visualmod$hudGrabY) / screenH;
            Hud hud = ModuleManager.HUD;
            if (this.visualmod$hudElement == 0) {
                hud.keybindsX.set(fx);
                hud.keybindsY.set(fy);
            } else {
                hud.targetX.set(fx);
                hud.targetY.set(fy);
            }
            return true;
        }
        HumanoidArm arm = this.visualmod$draggedArm;
        if (arm == null) {
            return super.mouseDragged(event, dx, dy);
        }
        ViewModel viewModel = ModuleManager.VIEW_MODEL;
        double fov = Math.toRadians(((Integer)this.minecraft.options.fov().get()).intValue());
        double depth = Math.max(0.2, (double)0.72f - viewModel.z(arm).get());
        double unitsPerGuiPixel = 2.0 * Math.tan(fov / 2.0) * depth / (double)this.height;
        viewModel.x(arm).set(viewModel.x(arm).get() + dx * unitsPerGuiPixel);
        viewModel.y(arm).set(viewModel.y(arm).get() - dy * unitsPerGuiPixel);
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.visualmod$hudElement >= 0) {
            this.visualmod$hudElement = -1;
            ModuleManager.save();
            return true;
        }
        if (this.visualmod$draggedArm != null) {
            this.visualmod$draggedArm = null;
            ModuleManager.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Inject(method={"mouseScrolled"}, at={@At(value="HEAD")}, cancellable=true)
    private void visualmod$resizeHand(double guiX, double guiY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        ViewModel viewModel = ModuleManager.VIEW_MODEL;
        if (!viewModel.isChatEditorActive() || scrollY == 0.0 || this.visualmod$overChatMessages(guiX, guiY)) {
            return;
        }
        HumanoidArm arm = this.visualmod$armAt(guiX);
        viewModel.size(arm).set(viewModel.size(arm).get() + Math.signum(scrollY) * 5.0);
        ModuleManager.save();
        cir.setReturnValue(true);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Inject(method={"render"}, at={@At(value="TAIL")})
    private void visualmod$renderHandHint(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        String text;
        ViewModel viewModel = ModuleManager.VIEW_MODEL;
        if (!viewModel.isChatEditorActive()) {
            return;
        }
        if (this.visualmod$draggedArm != null) {
            HumanoidArm arm = this.visualmod$draggedArm;
            text = String.format(Locale.ROOT, "%s \u0440\u0443\u043a\u0430   X %.2f   Y %.2f   %d%%", arm == HumanoidArm.RIGHT ? "\u041f\u0440\u0430\u0432\u0430\u044f" : "\u041b\u0435\u0432\u0430\u044f", viewModel.x(arm).get(), viewModel.y(arm).get(), viewModel.size(arm).getInt());
        } else {
            text = ModuleManager.HUD.isEnabled() ? "\u041b\u041a\u041c \u043f\u043e \u043f\u043b\u0430\u0448\u043a\u0435 \u2014 \u0434\u0432\u0438\u0433\u0430\u0442\u044c \u0435\u0451   \u00b7   \u041b\u041a\u041c \u2014 \u0434\u0432\u0438\u0433\u0430\u0442\u044c \u0440\u0443\u043a\u0443   \u00b7   \u043a\u043e\u043b\u0435\u0441\u043e \u2014 \u0440\u0430\u0437\u043c\u0435\u0440   \u00b7   \u041f\u041a\u041c \u2014 \u0441\u0431\u0440\u043e\u0441" : "\u041b\u041a\u041c \u2014 \u0434\u0432\u0438\u0433\u0430\u0442\u044c \u0440\u0443\u043a\u0443   \u00b7   \u043a\u043e\u043b\u0435\u0441\u043e \u2014 \u0440\u0430\u0437\u043c\u0435\u0440   \u00b7   \u041f\u041a\u041c \u2014 \u0441\u0431\u0440\u043e\u0441";
        }
        UiRenderer ui = UiRenderer.begin(graphics);
        try {
            int padX = ui.dp(14.0f);
            int h = ui.dp(28.0f);
            int w = ui.textWidth(UiRenderer.TextStyle.SMALL, text) + padX * 2;
            int x = (this.width * ui.guiScale() - w) / 2;
            int y = ui.dp(14.0f);
            ui.roundRectBordered(x, y, w, h, h / 2, Math.max(1, ui.dp(1.0f)), -14474453, -804582641);
            ui.textCentered(UiRenderer.TextStyle.SMALL, text, x + padX, y, h, -1644820);
        }
        finally {
            ui.end();
        }
    }
}

