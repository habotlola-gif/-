package dev.polt.client;

import dev.polt.client.module.Module;
import dev.polt.client.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public class PoltClient implements ClientModInitializer {
    public static final String NAME = "Polt Client";
    private static ModuleManager modules;
    private final AutoSwap autoSwap = new AutoSwap();

    public static ModuleManager modules() { return modules; }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        autoSwap.init();

        Map<Module, KeyBinding> binds = new LinkedHashMap<>();
        for (Module m : modules.all()) {
            binds.put(m, KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.poltclient." + m.getName().toLowerCase(),
                    InputUtil.Type.KEYSYM, m.getDefaultKey(), "category.poltclient")));
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            binds.forEach((m, key) -> { while (key.wasPressed()) m.toggle(); });
            modules.tick();
            autoSwap.tick(client);
        });

        // HUD: client name + list of enabled modules
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;
            var tr = mc.textRenderer;
            ctx.drawTextWithShadow(tr, NAME, 4, 4, 0xFF55FFFF);
            int y = 16;
            for (Module m : modules.all()) {
                if (!m.isEnabled()) continue;
                ctx.drawTextWithShadow(tr, m.getName(), 4, y, 0xFFFFFFFF);
                y += 10;
            }
        });
    }
}
