package com.example.stickykeys;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The sticky key logic: a key binding marked sticky latches on the first press (stays held
 * after you let go) and unlatches on the next press.
 */
public final class Sticky {

    private Sticky() {
    }

    // ------------------------------------------------------------------ settings
    public static boolean enabled = true;
    /** Let go of everything when a menu (inventory, chat...) opens. */
    public static boolean releaseInMenus = true;
    public static boolean hud = true;
    /** 0 = top left, 1 = top right, 2 = bottom left, 3 = bottom right, 4 = above the hotbar. */
    public static int hudCorner = 4;
    /** Ids of the bindings that are sticky ("key.forward", "key.attack"...). */
    public static final Set<String> sticky = new LinkedHashSet<>();

    /** Bindings currently latched down. */
    private static final Set<KeyBinding> latched = new LinkedHashSet<>();
    /** Physical keys currently held, to tell a new press from key-repeat. */
    private static final Set<InputUtil.Key> down = new HashSet<>();

    private static Path configFile;

    // ------------------------------------------------------------------ input

    /** A real key / mouse button changed (called before Minecraft updates the bindings). */
    public static void onPhysical(InputUtil.Key key, boolean pressed) {
        if (!pressed) {
            down.remove(key);
            return;
        }
        if (!down.add(key)) return;                        // already held: key repeat, not a new press
        if (!enabled) return;
        for (KeyBinding b : bindings()) {
            if (!sticky.contains(b.getId()) || b.getId().startsWith("key.stickykeys.")) continue;
            if (!key.equals(KeyBindingHelper.getBoundKeyOf(b))) continue;
            if (!latched.remove(b)) latched.add(b);        // press: latch, press again: unlatch
        }
    }

    /** After Minecraft updated the bindings for a key event: keep latched ones down. */
    public static void reapply() {
        if (!holding()) return;
        for (KeyBinding b : latched) b.setPressed(true);
    }

    /** Every tick, before the game reads its keys. */
    public static void tick(MinecraftClient client) {
        if (latched.isEmpty()) return;
        if (client.player == null || !enabled) {
            releaseAll();
            return;
        }
        if (client.currentScreen != null && releaseInMenus) {
            releaseAll();
            return;
        }
        latched.removeIf(b -> !sticky.contains(b.getId()));
        for (KeyBinding b : latched) b.setPressed(true);
    }

    private static boolean holding() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return enabled && mc.player != null && !(mc.currentScreen != null && releaseInMenus);
    }

    public static boolean isLatched(KeyBinding b) {
        return latched.contains(b);
    }

    public static List<KeyBinding> latched() {
        return new ArrayList<>(latched);
    }

    /** Let go of every latched key (keys you're really holding stay held). */
    public static void releaseAll() {
        if (latched.isEmpty()) return;
        for (KeyBinding b : latched) b.setPressed(false);
        latched.clear();
        KeyBinding.updatePressedStates();                  // back to what your keyboard says
        down.clear();
    }

    public static void setSticky(KeyBinding b, boolean on) {
        if (on) {
            sticky.add(b.getId());
        } else {
            sticky.remove(b.getId());
            if (latched.remove(b)) {
                b.setPressed(false);
                KeyBinding.updatePressedStates();
            }
        }
        save();
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) releaseAll();
        save();
    }

    public static List<KeyBinding> bindings() {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<KeyBinding> out = new ArrayList<>();
        if (mc.options == null) return out;
        for (KeyBinding b : mc.options.allKeys) if (!b.getId().startsWith("key.stickykeys.")) out.add(b);
        return out;
    }

    // ------------------------------------------------------------------ config

    public static void load() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("stickykeys.txt");
        if (!Files.exists(configFile)) {
            sticky.add("key.sneak");                       // a sensible starting point
            sticky.add("key.sprint");
            return;
        }
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                switch (k) {
                    case "enabled" -> enabled = v.equals("true");
                    case "releaseInMenus" -> releaseInMenus = v.equals("true");
                    case "hud" -> hud = v.equals("true");
                    case "hudCorner" -> hudCorner = Math.floorMod(Integer.parseInt(v), 5);
                    case "sticky" -> {
                        sticky.clear();
                        for (String id : v.split(",")) if (!id.isBlank()) sticky.add(id.trim());
                    }
                    default -> { }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        if (configFile == null) return;
        String s = "enabled=" + enabled + "\nreleaseInMenus=" + releaseInMenus + "\nhud=" + hud
                + "\nhudCorner=" + hudCorner + "\nsticky=" + String.join(",", sticky) + "\n";
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, s, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
