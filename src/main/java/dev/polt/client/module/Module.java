package dev.polt.client.module;

import net.minecraft.client.MinecraftClient;

public abstract class Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    private final String name;
    private final int defaultKey;
    private boolean enabled;

    protected Module(String name, int defaultKey) {
        this.name = name;
        this.defaultKey = defaultKey;
    }

    public String getName() { return name; }
    public int getDefaultKey() { return defaultKey; }
    public boolean isEnabled() { return enabled; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) onEnable(); else onDisable();
    }

    protected void onEnable() {}
    protected void onDisable() {}

    /** Called every client tick while the module is enabled and a world is loaded. */
    public void onTick() {}
}
