package com.visualmod.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.visualmod.VisualModClient;
import com.visualmod.module.AimAssist;
import com.visualmod.module.Ambience;
import com.visualmod.module.AspectRatio;
import com.visualmod.module.AutoMace;
import com.visualmod.module.AutoSwap;
import com.visualmod.module.ClickActions;
import com.visualmod.module.FTHelper;
import com.visualmod.module.FastExp;
import com.visualmod.module.Fullbright;
import com.visualmod.module.Hud;
import com.visualmod.module.KillAura;
import com.visualmod.module.Module;
import com.visualmod.module.NameTags;
import com.visualmod.module.NoJumpDelay;
import com.visualmod.module.Removals;
import com.visualmod.module.ShaderEsp;
import com.visualmod.module.ShaderHands;
import com.visualmod.module.Sprint;
import com.visualmod.module.SwingAnimations;
import com.visualmod.module.TargetEsp;
import com.visualmod.module.TriggerBot;
import com.visualmod.module.ViewModel;
import com.visualmod.setting.Setting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class ModuleManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("visualmod.json");
    public static final Fullbright FULLBRIGHT = new Fullbright();
    public static final NoJumpDelay NO_JUMP_DELAY = new NoJumpDelay();
    public static final FastExp FAST_EXP = new FastExp();
    public static final Sprint SPRINT = new Sprint();
    public static final ShaderHands SHADER_HANDS = new ShaderHands();
    public static final ShaderEsp SHADER_ESP = new ShaderEsp();
    public static final NameTags NAME_TAGS = new NameTags();
    public static final AutoMace AUTO_MACE = new AutoMace();
    public static final Removals REMOVALS = new Removals();
    public static final SwingAnimations SWING_ANIMATIONS = new SwingAnimations();
    public static final ViewModel VIEW_MODEL = new ViewModel();
    public static final Ambience AMBIENCE = new Ambience();
    public static final TriggerBot TRIGGER_BOT = new TriggerBot();
    public static final AimAssist AIM_ASSIST = new AimAssist();
    public static final KillAura KILL_AURA = new KillAura();
    public static final AutoSwap AUTO_SWAP = new AutoSwap();
    public static final FTHelper FT_HELPER = new FTHelper();
    public static final ClickActions CLICK_ACTIONS = new ClickActions();
    public static final Hud HUD = new Hud();
    public static final TargetEsp TARGET_ESP = new TargetEsp();
    public static final AspectRatio ASPECT_RATIO = new AspectRatio();
    public static final Experimental EXPERIMENTAL = new Experimental();
    public static final List<Module> MODULES = List.of(FULLBRIGHT, SPRINT, NO_JUMP_DELAY, FAST_EXP, SHADER_HANDS, SHADER_ESP, NAME_TAGS, AUTO_MACE, REMOVALS, SWING_ANIMATIONS, VIEW_MODEL, AMBIENCE, TRIGGER_BOT, AIM_ASSIST, KILL_AURA, AUTO_SWAP, FT_HELPER, CLICK_ACTIONS, HUD, TARGET_ESP, ASPECT_RATIO, EXPERIMENTAL);
    public static boolean actedThisTick;

    private ModuleManager() {
    }

    public static void tick(Minecraft client) {
        for (Module module : MODULES) {
            if (!module.isEnabled() && (module != CLICK_ACTIONS || !CLICK_ACTIONS.isBusy())) continue;
            module.onTick(client);
        }
    }

    public static void onBindPressed(int bind) {
        boolean changed = false;
        for (Module module : MODULES) {
            if (module.getBind() == bind) {
                module.toggle();
                changed = true;
            }
            module.onBind(bind);
        }
        if (changed) {
            ModuleManager.save();
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG, new LinkOption[0])) {
            return;
        }
        try {
            JsonObject json = (JsonObject)GSON.fromJson(Files.readString(CONFIG), JsonObject.class);
            if (json == null) {
                return;
            }
            for (Module module : MODULES) {
                JsonElement entry = json.get(module.getName());
                if (entry == null) continue;
                if (entry.isJsonPrimitive()) {
                    module.setEnabled(entry.getAsBoolean());
                    continue;
                }
                JsonObject object = entry.getAsJsonObject();
                if (object.has("enabled")) {
                    module.setEnabled(object.get("enabled").getAsBoolean());
                }
                if (object.has("bind")) {
                    module.setBind(object.get("bind").getAsInt());
                }
                JsonObject settings = object.has("settings") ? object.getAsJsonObject("settings") : new JsonObject();
                for (Setting<?> setting : module.getSettings()) {
                    if (!settings.has(setting.getId())) continue;
                    try {
                        setting.load(settings.get(setting.getId()));
                    }
                    catch (Exception e) {
                        VisualModClient.LOGGER.warn("\u041d\u0435\u0432\u0435\u0440\u043d\u043e\u0435 \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0435 {}.{} \u0432 \u043a\u043e\u043d\u0444\u0438\u0433\u0435", (Object)module.getName(), (Object)setting.getId());
                    }
                }
            }
        }
        catch (Exception e) {
            VisualModClient.LOGGER.warn("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043f\u0440\u043e\u0447\u0438\u0442\u0430\u0442\u044c {}", (Object)CONFIG, (Object)e);
        }
    }

    public static void save() {
        JsonObject json = new JsonObject();
        for (Module module : MODULES) {
            JsonObject object = new JsonObject();
            object.addProperty("enabled", Boolean.valueOf(module.isEnabled()));
            object.addProperty("bind", (Number)module.getBind());
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                settings.add(setting.getId(), setting.save());
            }
            object.add("settings", (JsonElement)settings);
            json.add(module.getName(), (JsonElement)object);
        }
        try {
            Files.writeString(CONFIG, (CharSequence)GSON.toJson((JsonElement)json), new OpenOption[0]);
        }
        catch (IOException e) {
            VisualModClient.LOGGER.warn("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0441\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c {}", (Object)CONFIG, (Object)e);
        }
    }
}

