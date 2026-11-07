package com.visualmod.module;

import com.visualmod.setting.ModeSetting;
import com.visualmod.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * AirStuck под Grim: «висение» в воздухе на несколько тиков.
 *
 * Пока модуль включён и ты в воздухе, игрок замирает на месте, а пакеты движения не отправляются.
 * Через «Время» секунд висение само отпускает: возвращается движение, с которым ты начал висеть, так что
 * на сервере следующий пакет продолжает предсказанную траекторию (иначе Grim откатит). Повторное висение
 * начнётся только после приземления или после повторного включения. Чем дольше висишь, тем выше риск, что античит заметит паузу.
 */
public final class AirStuck extends Module {
    public final ModeSetting mode = this.setting(new ModeSetting("mode", "Режим", "С лимитом", "Без лимита"));
    public final ModeSetting exit = this.setting(new ModeSetting("exit", "После выхода", "Продолжить падение", "С нуля (как в KM)"));
    public final NumberSetting limit = this.setting(new NumberSetting("seconds", "Время", 12.0, 0.5, 20.0, 0.5, " с"));

    private boolean frozen;     // сейчас висим: пакеты движения глушатся
    private boolean released;   // лимит выбран, ждём приземления
    private int stuckTicks;
    private Vec3 savedPos = Vec3.ZERO;
    private Vec3 savedMotion = Vec3.ZERO;
    private double savedFall;

    public AirStuck() {
        super("AirStuck", "Зависание в воздухе: с лимитом по времени или пока не выключишь", Category.MOVEMENT);
        this.limit.visibleWhen(() -> this.mode.getIndex() == 0);
    }

    /** Нужно ли сейчас глушить пакеты движения (ClientCommonPacketListenerImplMixin). */
    public boolean blockPackets() {
        return this.frozen;
    }

    private static boolean unavailable(LocalPlayer player) {
        return player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()
                || player.isPassenger() || player.getAbilities().flying || player.isFallFlying() || !player.isAlive();
    }

    /** HEAD LocalPlayer.tick. */
    public void beforePlayerTick(LocalPlayer player) {
        if (!this.isEnabled() || unavailable(player)) {
            this.finish(player);
            return;
        }
        if (this.released) return;
        // Начинаем висеть только на падении: сервер считает «парение» по последнему пакету движения, и если
        // он был движением вверх, через 4 секунды без пакетов (80 тиков) кикнет за полёт.
        if (!this.frozen && player.getDeltaMovement().y > -0.05) return;
        if (!this.frozen) {
            this.frozen = true;
            this.savedPos = player.position();
            this.savedMotion = player.getDeltaMovement();
            this.savedFall = player.fallDistance;
            this.stuckTicks = 0;
        }
    }

    /** RETURN LocalPlayer.tick. */
    public void afterPlayerTick(LocalPlayer player) {
        if (!this.frozen) return;
        // откатываем всё, что успела сделать физика этого тика
        player.setPos(this.savedPos.x, this.savedPos.y, this.savedPos.z);
        player.xo = this.savedPos.x;
        player.yo = this.savedPos.y;
        player.zo = this.savedPos.z;
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = this.savedFall;
        if (this.mode.getIndex() == 0 && ++this.stuckTicks >= Math.max(1, Math.round(this.limit.getFloat() * 20.0f))) {
            player.setDeltaMovement(this.exit.getIndex() == 0 ? this.savedMotion : Vec3.ZERO);
            this.frozen = false;
            this.released = true;
        }
    }

    private void finish(LocalPlayer player) {
        if (this.frozen) {
            player.setDeltaMovement(this.exit.getIndex() == 0 ? this.savedMotion : Vec3.ZERO);
        }
        this.frozen = false;
        this.released = false;
        this.stuckTicks = 0;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!enabled && mc.player != null) {
            this.finish(mc.player);
        } else {
            this.released = false;
        }
    }
}
