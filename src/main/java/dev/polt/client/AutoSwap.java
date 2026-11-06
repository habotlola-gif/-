package dev.polt.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AutoSwap: правило = ключевые слова + клавиша. Предмет подходит, если в его названии есть любое слово.
 * Бинд: если в оффхенде уже предмет этой категории — берём из хотбара ДРУГОЙ предмет той же категории
 * (сфера A -> сфера B). Если в оффхенде что-то другое (например талисман) — берём первый подходящий
 * (талисман -> сфера). Свап штатный "swap with offhand". Правила: config/poltclient-swaps.json.
 */
public class AutoSwap {
    public static class Rule {
        public List<String> keywords; // слова в названии предмета (без учёта регистра)
        public int key;               // GLFW-код клавиши
        public Rule(List<String> keywords, int key) { this.keywords = keywords; this.key = key; }
        String label() { return String.join(" / ", keywords); }
    }

    private static final int COOLDOWN_TICKS = 4;

    private final List<Rule> rules = new ArrayList<>();
    private final List<KeyBinding> binds = new ArrayList<>();
    private int cooldown;

    public void init() {
        loadRules();
        for (Rule r : rules) {
            binds.add(KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "Swap: " + r.label(), InputUtil.Type.KEYSYM, r.key, "category.poltclient")));
        }
    }

    private void loadRules() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("poltclient-swaps.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (!Files.exists(file)) {
                List<Rule> defaults = List.of(
                        new Rule(List.of("сфера", "шар"), GLFW.GLFW_KEY_Z),
                        new Rule(List.of("талисман", "тотем", "totem"), GLFW.GLFW_KEY_X));
                Files.writeString(file, gson.toJson(defaults));
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                List<Rule> loaded = gson.fromJson(reader, new TypeToken<List<Rule>>() {}.getType());
                if (loaded != null) rules.addAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("[PoltClient] Не удалось прочитать конфиг свапа: " + e);
        }
    }

    public void tick(MinecraftClient mc) {
        if (cooldown > 0) cooldown--;
        for (int i = 0; i < binds.size(); i++) {
            while (binds.get(i).wasPressed()) {
                if (cooldown == 0 && mc.player != null && mc.currentScreen == null) {
                    swap(mc, rules.get(i));
                    cooldown = COOLDOWN_TICKS;
                }
            }
        }
    }

    private boolean matches(ItemStack s, Rule r) {
        if (s.isEmpty()) return false;
        String n = s.getName().getString().toLowerCase();
        for (String k : r.keywords) if (n.contains(k.toLowerCase())) return true;
        return false;
    }

    private void swap(MinecraftClient mc, Rule r) {
        var p = mc.player;
        ItemStack off = p.getOffHandStack();
        boolean offMatches = matches(off, r);
        String offName = off.getName().getString();

        // в оффхенде уже эта категория -> ищем другой предмет; иначе -> любой подходящий
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!matches(st, r)) continue;
            if (offMatches && st.getName().getString().equals(offName)) continue;
            slot = i;
            break;
        }
        if (slot == -1) {
            p.sendMessage(Text.literal("[Polt] В хотбаре нет подходящего предмета: " + r.label()), true);
            return;
        }

        int original = p.getInventory().selectedSlot;
        var net = p.networkHandler;
        net.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        net.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        net.sendPacket(new UpdateSelectedSlotC2SPacket(original));
    }
}
