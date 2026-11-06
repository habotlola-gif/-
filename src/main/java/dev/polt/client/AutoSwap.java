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
 * AutoSwap: бинд переключает предмет в оффхенде по кругу между названиями из правила.
 * names = [A, B]: в оффхенде A -> берём B, в оффхенде B -> берём A, иначе берём A.
 * names = [X]: просто положить X в оффхенд (например "талик -> сфера").
 * Предмет берётся из хотбара и меняется с оффхендом (штатный "swap with offhand").
 * Правила лежат в config/poltclient-swaps.json.
 */
public class AutoSwap {
    public static class Rule {
        public List<String> names; // части названий предметов (без учёта регистра)
        public int key;            // GLFW-код клавиши
        public Rule(List<String> names, int key) { this.names = names; this.key = key; }
        String label() { return String.join(" / ", names); }
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
                        new Rule(List.of("Сфера A", "Сфера B"), GLFW.GLFW_KEY_Z),
                        new Rule(List.of("Талисман A", "Талисман B"), GLFW.GLFW_KEY_X),
                        new Rule(List.of("Сфера A"), GLFW.GLFW_KEY_C));
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

    private boolean matches(ItemStack s, String name) {
        return !s.isEmpty() && s.getName().getString().toLowerCase().contains(name.toLowerCase());
    }

    private void swap(MinecraftClient mc, Rule r) {
        var p = mc.player;

        // что сейчас в оффхенде -> что нужно взять
        String want = r.names.get(0);
        for (int k = 0; k < r.names.size(); k++) {
            if (matches(p.getOffHandStack(), r.names.get(k))) {
                if (r.names.size() == 1) return; // уже лежит нужное
                want = r.names.get((k + 1) % r.names.size());
                break;
            }
        }

        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (matches(p.getInventory().getStack(i), want)) { slot = i; break; }
        }
        if (slot == -1) {
            p.sendMessage(Text.literal("[Polt] В хотбаре нет предмета: " + want), true);
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
