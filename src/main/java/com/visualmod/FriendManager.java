package com.visualmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.visualmod.VisualModClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;

public final class FriendManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("visualmod-friends.json");
    private static final Set<String> FRIENDS = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

    private FriendManager() {
    }

    public static boolean isFriend(String name) {
        return name != null && FRIENDS.contains(name.strip());
    }

    public static boolean add(String name) {
        if (FRIENDS.add(name.strip())) {
            FriendManager.save();
            return true;
        }
        return false;
    }

    public static boolean remove(String name) {
        if (FRIENDS.remove(name.strip())) {
            FriendManager.save();
            return true;
        }
        return false;
    }

    public static void clear() {
        FRIENDS.clear();
        FriendManager.save();
    }

    public static List<String> all() {
        return Collections.unmodifiableList(new ArrayList<String>(FRIENDS));
    }

    public static void load() {
        if (!Files.exists(FILE, new LinkOption[0])) {
            return;
        }
        try {
            List names = (List)GSON.fromJson(Files.readString(FILE), new TypeToken<List<String>>(){}.getType());
            if (names != null) {
                FRIENDS.clear();
                for (String name : names) {
                    if (name == null || name.isBlank()) continue;
                    FRIENDS.add(name.strip());
                }
            }
        }
        catch (Exception e) {
            VisualModClient.LOGGER.warn("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043f\u0440\u043e\u0447\u0438\u0442\u0430\u0442\u044c {}", (Object)FILE, (Object)e);
        }
    }

    public static void save() {
        try {
            Files.writeString(FILE, (CharSequence)GSON.toJson(new ArrayList<String>(FRIENDS)), new OpenOption[0]);
        }
        catch (IOException e) {
            VisualModClient.LOGGER.warn("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0441\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c {}", (Object)FILE, (Object)e);
        }
    }
}

