package dev.polt.client.module;

import dev.polt.client.module.impl.Fly;
import dev.polt.client.module.impl.Fullbright;
import dev.polt.client.module.impl.Sprint;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class ModuleManager {
    private final List<Module> modules = List.of(
            new Sprint(GLFW.GLFW_KEY_R),
            new Fly(GLFW.GLFW_KEY_G),
            new Fullbright(GLFW.GLFW_KEY_B)
    );

    public List<Module> all() { return modules; }

    public void tick() {
        if (Module.mc.player == null || Module.mc.world == null) return;
        for (Module m : modules) {
            if (m.isEnabled()) m.onTick();
        }
    }
}
