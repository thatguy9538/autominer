package com.example.multibind;

import com.example.multibind.mixin.KeyBindingAccessor;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extra keys for key bindings. A binding's own key still works as normal; any extra key you add
 * presses, holds and clicks the same binding.
 */
public final class MultiBind {

    private MultiBind() {
    }

    /** Most extra keys one binding can have. */
    public static final int MAX_EXTRA = 4;

    /** Binding id ("key.jump") -> its extra keys. */
    public static final Map<String, List<InputUtil.Key>> EXTRA = new LinkedHashMap<>();

    private static Path configFile;

    // ------------------------------------------------------------------ input (called from the mixin)

    /** A real key / mouse button went down or up (after Minecraft handled it). */
    public static void onKey(InputUtil.Key key, boolean pressed) {
        if (EXTRA.isEmpty()) return;
        for (KeyBinding b : bindings()) {
            List<InputUtil.Key> extras = EXTRA.get(b.getId());
            if (extras == null || extras.isEmpty()) continue;
            boolean isExtra = extras.contains(key);
            boolean isMain = key.equals(KeyBindingHelper.getBoundKeyOf(b));
            if (!isExtra && !isMain) continue;
            if (pressed && isExtra) {
                b.setPressed(true);
            } else if (!pressed) {
                // let go of one key: the binding stays down while its own key or another extra is held
                b.setPressed(anyHeld(b));
            }
        }
    }

    /** A real key / mouse button was clicked: count it as a click on every binding it's an extra for. */
    public static void onClick(InputUtil.Key key) {
        if (EXTRA.isEmpty()) return;
        for (KeyBinding b : bindings()) {
            List<InputUtil.Key> extras = EXTRA.get(b.getId());
            if (extras == null || !extras.contains(key)) continue;
            if (key.equals(KeyBindingHelper.getBoundKeyOf(b))) continue;   // already counted by Minecraft
            KeyBindingAccessor acc = (KeyBindingAccessor) b;
            acc.multibind$setTimesPressed(acc.multibind$getTimesPressed() + 1);
        }
    }

    /** Minecraft re-read every binding from the keyboard (e.g. a menu closed): add the extras back. */
    public static void resync() {
        if (EXTRA.isEmpty()) return;
        for (KeyBinding b : bindings()) {
            List<InputUtil.Key> extras = EXTRA.get(b.getId());
            if (extras == null) continue;
            for (InputUtil.Key k : extras) {
                if (isDown(k)) {
                    b.setPressed(true);
                    break;
                }
            }
        }
    }

    /** Is the binding's own key or any of its extras physically held right now? */
    private static boolean anyHeld(KeyBinding b) {
        if (isDown(KeyBindingHelper.getBoundKeyOf(b))) return true;
        for (InputUtil.Key k : EXTRA.getOrDefault(b.getId(), List.of())) if (isDown(k)) return true;
        return false;
    }

    /** Asks the keyboard / mouse directly whether a key is held. */
    public static boolean isDown(InputUtil.Key key) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (key == null || key.equals(InputUtil.UNKNOWN_KEY) || mc.getWindow() == null) return false;
        long handle = mc.getWindow().getHandle();
        int code = key.getCode();
        if (key.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(handle, code) == GLFW.GLFW_PRESS;
        if (key.getCategory() == InputUtil.Type.KEYSYM && code >= 0) return GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
        return false;
    }

    // ------------------------------------------------------------------ editing

    public static List<InputUtil.Key> extras(KeyBinding b) {
        return EXTRA.getOrDefault(b.getId(), List.of());
    }

    /** Adds an extra key; false if it's already this binding's key or extra, or the binding is full. */
    public static boolean add(KeyBinding b, InputUtil.Key key) {
        if (key.equals(KeyBindingHelper.getBoundKeyOf(b))) return false;
        List<InputUtil.Key> list = EXTRA.computeIfAbsent(b.getId(), id -> new ArrayList<>());
        if (list.contains(key) || list.size() >= MAX_EXTRA) return false;
        list.add(key);
        save();
        return true;
    }

    public static void remove(KeyBinding b, InputUtil.Key key) {
        List<InputUtil.Key> list = EXTRA.get(b.getId());
        if (list == null) return;
        list.remove(key);
        if (list.isEmpty()) EXTRA.remove(b.getId());
        b.setPressed(anyHeld(b));
        save();
    }

    public static void clearAll() {
        EXTRA.clear();
        KeyBinding.updatePressedStates();
        save();
    }

    /** Other bindings whose own key or extra key is this key (to warn about clashes). */
    public static List<KeyBinding> usedBy(InputUtil.Key key, KeyBinding except) {
        List<KeyBinding> out = new ArrayList<>();
        for (KeyBinding b : bindings()) {
            if (b == except) continue;
            if (key.equals(KeyBindingHelper.getBoundKeyOf(b)) || extras(b).contains(key)) out.add(b);
        }
        return out;
    }

    public static List<KeyBinding> bindings() {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<KeyBinding> out = new ArrayList<>();
        if (mc.options == null) return out;
        for (KeyBinding b : mc.options.allKeys) out.add(b);
        return out;
    }

    // ------------------------------------------------------------------ saving (config/multibind.txt)

    public static void load() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("multibind.txt");
        if (!Files.exists(configFile)) return;
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) continue;
                String id = line.substring(0, eq).trim();
                List<InputUtil.Key> keys = new ArrayList<>();
                for (String k : line.substring(eq + 1).split(",")) {
                    k = k.trim();
                    if (k.isEmpty()) continue;
                    try {
                        InputUtil.Key key = InputUtil.fromTranslationKey(k);
                        if (!key.equals(InputUtil.UNKNOWN_KEY) && !keys.contains(key)) keys.add(key);
                    } catch (Exception ignored) {
                    }
                }
                if (!keys.isEmpty()) EXTRA.put(id, keys);
            }
        } catch (Exception e) {
            MultiBindClient.LOG.warn("Couldn't read {}", configFile, e);
        }
    }

    public static void save() {
        if (configFile == null) return;
        StringBuilder sb = new StringBuilder("# Multi Bind: extra keys per key binding (binding id = keys)\n");
        EXTRA.forEach((id, keys) -> {
            sb.append(id).append('=');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(keys.get(i).getTranslationKey());
            }
            sb.append('\n');
        });
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            MultiBindClient.LOG.warn("Couldn't save {}", configFile, e);
        }
    }
}
