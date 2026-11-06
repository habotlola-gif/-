package dev.polt.client.module.impl;

import dev.polt.client.module.Module;

/** Creative-style flight. Only works where the server doesn't enforce movement checks. */
public class Fly extends Module {
    public Fly(int key) { super("Fly", key); }

    @Override
    public void onTick() {
        mc.player.getAbilities().allowFlying = true;
    }

    @Override
    protected void onDisable() {
        var p = mc.player;
        if (p == null) return;
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().allowFlying = false;
            p.getAbilities().flying = false;
        }
    }
}
