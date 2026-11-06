package com.visualmod.module;

import com.google.gson.JsonObject;
import com.visualmod.discord.DiscordIpc;
import com.visualmod.setting.BooleanSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence.
 *
 * Нужно один раз создать приложение на https://discord.com/developers/applications и записать его
 * Application ID в файл config/visualmod-discord.txt (создаётся при первом включении модуля).
 * Картинку (m_logo.png из docs/discord) загрузи в Rich Presence -> Art Assets под ключом "m_logo".
 */
public final class DiscordRpc extends Module {
    private static final String LOGO_KEY = "m_logo";
    /** Application ID по умолчанию (публичный). Можно переопределить в config/visualmod-discord.txt. */
    private static final String DEFAULT_CLIENT_ID = "1568344662866001940";
    private static final int UPDATE_EVERY_TICKS = 100; // раз в 5 секунд

    public final BooleanSetting showServer = this.setting(new BooleanSetting("show_server", "Показывать адрес сервера", false));
    public final BooleanSetting showModules = this.setting(new BooleanSetting("show_modules", "Показывать включённые боевые модули", true));

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VisualMod-DiscordRPC");
        t.setDaemon(true);
        return t;
    });
    private DiscordIpc ipc;
    private long startedAt;
    private int ticks;
    private String lastSent = "";
    private String imageKey = LOGO_KEY;

    public DiscordRpc() {
        super("DiscordRPC", "Статус в Discord: что делаешь и какие модули включены", Category.MISC);
    }

    @Override
    public void setEnabled(boolean enabled) {
        boolean was = this.isEnabled();
        super.setEnabled(enabled);
        if (enabled && !was) {
            this.startedAt = System.currentTimeMillis() / 1000L;
            this.ticks = UPDATE_EVERY_TICKS;
            this.lastSent = "";
            this.imageKey = readImage();
        } else if (!enabled && was) {
            this.worker.execute(this::shutdown);
        }
    }

    @Override
    public void onTick(Minecraft client) {
        if (++this.ticks < UPDATE_EVERY_TICKS) return;
        this.ticks = 0;

        JsonObject activity = buildActivity(client);
        String key = activity.toString();
        if (key.equals(this.lastSent)) return;
        this.lastSent = key;
        this.worker.execute(() -> send(activity));
    }

    private JsonObject buildActivity(Minecraft client) {
        String details;
        if (client.level == null) {
            details = "В главном меню";
        } else if (client.isLocalServer() || client.hasSingleplayerServer()) {
            details = "Одиночная игра";
        } else {
            ServerData server = client.getCurrentServer();
            details = this.showServer.isOn() && server != null ? "Играет на " + server.ip : "Играет на сервере";
        }

        String state = "Visual Mod";
        if (this.showModules.isOn()) {
            List<String> names = new ArrayList<>();
            for (Module m : ModuleManager.MODULES) {
                if (m.isEnabled() && m.getCategory() == Category.COMBAT) names.add(m.getName());
            }
            state = names.isEmpty() ? "Без модулей"
                    : String.join(", ", names.size() > 3 ? names.subList(0, 3) : names)
                    + (names.size() > 3 ? " +" + (names.size() - 3) : "");
        }

        JsonObject activity = new JsonObject();
        activity.addProperty("details", details);
        activity.addProperty("state", state);
        JsonObject ts = new JsonObject();
        ts.addProperty("start", this.startedAt);
        activity.add("timestamps", ts);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", this.imageKey);
        assets.addProperty("large_text", "Visual Mod");
        activity.add("assets", assets);
        return activity;
    }

    // --- фоновый поток ---

    private void send(JsonObject activity) {
        try {
            if (this.ipc == null || !this.ipc.isConnected()) {
                String id = readClientId();
                if (id == null) return;
                this.ipc = new DiscordIpc(id);
                if (!this.ipc.connect()) {
                    this.ipc = null;
                    this.lastSent = ""; // повторим при следующем тике (Discord мог быть не запущен)
                    return;
                }
            }
            this.ipc.setActivity(activity);
        } catch (Exception ignored) {
            this.ipc = null;
            this.lastSent = "";
        }
    }

    private void shutdown() {
        if (this.ipc != null) {
            this.ipc.close();
            this.ipc = null;
        }
    }

    /**
     * Картинка статуса: строка вида "image=m_logo" или "image=https://.../anim.gif" в
     * config/visualmod-discord.txt. По умолчанию ключ m_logo (статичная картинка из Art Assets).
     */
    private static String readImage() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("visualmod-discord.txt");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String s = line.strip();
                    if (s.startsWith("image=") && s.length() > 6) return s.substring(6).strip();
                }
            }
        } catch (IOException ignored) {
        }
        return LOGO_KEY;
    }

    /** Application ID из config/visualmod-discord.txt. Если файла нет — создаёт с подсказкой. */
    private static String readClientId() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("visualmod-discord.txt");
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, "# Application ID по умолчанию уже зашит в мод. Чтобы использовать свой, впиши его строкой id=ЦИФРЫ\n"
                        + "# Анимированная иконка: image=https://прямая-ссылка/на/файл.gif\n");
                return DEFAULT_CLIENT_ID;
            }
            for (String line : Files.readAllLines(file)) {
                String s = line.strip();
                if (s.startsWith("id=")) s = s.substring(3).strip();
                if (!s.isEmpty() && !s.startsWith("#") && s.chars().allMatch(Character::isDigit)) return s;
            }
        } catch (IOException ignored) {
        }
        return DEFAULT_CLIENT_ID;
    }
}
