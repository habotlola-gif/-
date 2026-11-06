package com.visualmod;

import com.mojang.blaze3d.platform.InputConstants;
import com.visualmod.FriendManager;
import com.visualmod.gui.ClickGuiScreen;
import com.visualmod.module.ModuleManager;
import com.visualmod.render.HudRenderer;
import com.visualmod.render.NameTagsRenderer;
import com.visualmod.render.ParticlesRenderer;
import com.visualmod.render.TargetEspRenderer;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VisualModClient
implements ClientModInitializer {
    public static final String MOD_ID = "visualmod";
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"visualmod");
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register((Identifier)Identifier.fromNamespaceAndPath((String)"visualmod", (String)"main"));
    public static KeyMapping openGuiKey;
    private static final String FRIEND_PREFIX = ".friend";

    public void onInitializeClient() {
        ModuleManager.load();
        FriendManager.load();
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.strip();
            if (trimmed.toLowerCase(Locale.ROOT).equals(FRIEND_PREFIX) || trimmed.toLowerCase(Locale.ROOT).startsWith(".friend ")) {
                VisualModClient.handleFriendCommand(trimmed);
                return false;
            }
            return true;
        });
        openGuiKey = KeyBindingHelper.registerKeyBinding((KeyMapping)new KeyMapping("key.visualmod.open_gui", InputConstants.Type.KEYSYM, 344, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.consumeClick()) {
                if (client.screen != null) continue;
                client.setScreen((Screen)new ClickGuiScreen());
            }
            ModuleManager.tick(client);
        });
        HudElementRegistry.addFirst((Identifier)Identifier.fromNamespaceAndPath((String)MOD_ID, (String)"name_tags"), NameTagsRenderer::render);
        HudElementRegistry.addFirst((Identifier)Identifier.fromNamespaceAndPath((String)MOD_ID, (String)"target_esp"), TargetEspRenderer::render);
        HudElementRegistry.addFirst((Identifier)Identifier.fromNamespaceAndPath((String)MOD_ID, (String)"particles"), ParticlesRenderer::render);
        HudElementRegistry.attachElementBefore((Identifier)VanillaHudElements.CHAT, (Identifier)Identifier.fromNamespaceAndPath((String)MOD_ID, (String)"hud"), HudRenderer::render);
    }

    private static void handleFriendCommand(String command) {
        String[] parts = command.split("\\s+", 3);
        String action = parts.length >= 2 ? parts[1].toLowerCase(Locale.ROOT) : "";
        String name = parts.length >= 3 ? parts[2].strip() : "";
        switch (action) {
            case "add": {
                if (name.isEmpty()) {
                    VisualModClient.reply("\u0423\u043a\u0430\u0436\u0438 \u043d\u0438\u043a: .friend add <\u043d\u0438\u043a>");
                    break;
                }
                VisualModClient.reply(FriendManager.add(name) ? name + " \u0434\u043e\u0431\u0430\u0432\u043b\u0435\u043d \u0432 \u0434\u0440\u0443\u0437\u044c\u044f" : name + " \u0443\u0436\u0435 \u0432 \u0434\u0440\u0443\u0437\u044c\u044f\u0445");
                break;
            }
            case "del": 
            case "delete": 
            case "remove": 
            case "rem": {
                if (name.isEmpty()) {
                    VisualModClient.reply("\u0423\u043a\u0430\u0436\u0438 \u043d\u0438\u043a: .friend del <\u043d\u0438\u043a>");
                    break;
                }
                VisualModClient.reply(FriendManager.remove(name) ? name + " \u0443\u0431\u0440\u0430\u043d \u0438\u0437 \u0434\u0440\u0443\u0437\u0435\u0439" : name + " \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d \u0432 \u0434\u0440\u0443\u0437\u044c\u044f\u0445");
                break;
            }
            case "list": {
                List<String> friends = FriendManager.all();
                VisualModClient.reply((String)(friends.isEmpty() ? "\u0421\u043f\u0438\u0441\u043e\u043a \u0434\u0440\u0443\u0437\u0435\u0439 \u043f\u0443\u0441\u0442" : "\u0414\u0440\u0443\u0437\u044c\u044f (" + friends.size() + "): " + String.join((CharSequence)", ", friends)));
                break;
            }
            case "clear": {
                FriendManager.clear();
                VisualModClient.reply("\u0421\u043f\u0438\u0441\u043e\u043a \u0434\u0440\u0443\u0437\u0435\u0439 \u043e\u0447\u0438\u0449\u0435\u043d");
                break;
            }
            default: {
                VisualModClient.reply("\u041a\u043e\u043c\u0430\u043d\u0434\u044b: .friend add <\u043d\u0438\u043a> \u00b7 del <\u043d\u0438\u043a> \u00b7 list \u00b7 clear");
            }
        }
    }

    private static void reply(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage((Component)Component.literal((String)("[\u0414\u0440\u0443\u0437\u044c\u044f] " + text)));
        }
    }
}

