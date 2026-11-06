package dev.polt.client.module.impl;

import dev.polt.client.module.Module;

public class Sprint extends Module {
    public Sprint(int key) { super("Sprint", key); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (p.input.hasForwardMovement() && !p.horizontalCollision && !p.isSneaking()) {
            p.setSprinting(true);
        }
    }
}
