package com.example.powderassist;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Powder Assist: helpers for powder grinding in the Crystal Hollows.
 * U = menu, J = toggle hardstone auto-mine, N = toggle chest solver.
 */
public class PowderAssistClient implements ClientModInitializer {

    public static final String MOD_ID = "powderassist";

    // ------------------------------------------------------------------ settings
    public static boolean autoMine = false;          // toggled with J, not saved
    public static boolean mineHardstone = true;
    public static boolean mineGemstones = false;
    public static boolean mineOres = false;
    /** 1 (slow) .. 10 (fast): how quickly the camera turns while auto-mining. */
    public static int turnSpeed = 5;

    public static boolean solver = true;
    public static boolean solverAutoClick = true;
    /** Lockpick with right-click (true) or left-click (false). */
    public static boolean solverRightClick = true;

    public static boolean esp = true;
    public static boolean tracers = true;
    public static boolean hud = true;
    /** 0 = top left, 1 = top right, 2 = bottom left, 3 = bottom right. */
    public static int hudCorner = 0;

    private static KeyBinding menuKey, mineKey, solverKey;
    private static Path configFile;

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("powderassist.txt");
        load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.powderassist.menu",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, cat));
        mineKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.powderassist.mine",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
        solverKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.powderassist.solver",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, cat));

        ClientTickEvents.END_CLIENT_TICK.register(PowderAssistClient::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String s = Formatting.strip(message.getString());
            if (s == null) return;
            Chests.onChat(s);
            Tracker.onChat(s);
        });
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), Hud::render);
    }

    private static void onTick(MinecraftClient client) {
        while (menuKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new PowderScreen());
        }
        while (mineKey.wasPressed()) setAutoMine(client, !autoMine);
        while (solverKey.wasPressed()) {
            solver = !solver;
            save();
            say(client, "Chest solver: ", solver);
        }
        if (client.player == null || client.world == null) {
            if (autoMine) setAutoMine(client, false);
            return;
        }
        Tracker.tick(client);
        Chests.tick(client);
        Miner.tick(client);
    }

    public static void setAutoMine(MinecraftClient client, boolean on) {
        if (on && !mineHardstone && !mineGemstones && !mineOres) {
            if (client.player != null) client.player.sendMessage(Text.literal("[Powder Assist] ")
                    .formatted(Formatting.AQUA).append(Text.literal("Pick something to mine in the menu (U) first.")
                            .formatted(Formatting.RED)), false);
            return;
        }
        autoMine = on;
        if (!on) Miner.stop(client);
        say(client, "Auto-mine: ", on);
    }

    static void say(MinecraftClient client, String what, boolean on) {
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("[Powder Assist] ").formatted(Formatting.AQUA)
                .append(Text.literal(what).formatted(Formatting.WHITE))
                .append(on ? Text.literal("ON").formatted(Formatting.GREEN) : Text.literal("OFF").formatted(Formatting.RED)),
                true);
    }

    // ------------------------------------------------------------------ config

    public static void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mineHardstone", mineHardstone);
        m.put("mineGemstones", mineGemstones);
        m.put("mineOres", mineOres);
        m.put("turnSpeed", turnSpeed);
        m.put("solver", solver);
        m.put("solverAutoClick", solverAutoClick);
        m.put("solverRightClick", solverRightClick);
        m.put("esp", esp);
        m.put("tracers", tracers);
        m.put("hud", hud);
        m.put("hudCorner", hudCorner);
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static void load() {
        if (!Files.exists(configFile)) return;
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                boolean b = v.equals("true");
                switch (k) {
                    case "mineHardstone" -> mineHardstone = b;
                    case "mineGemstones" -> mineGemstones = b;
                    case "mineOres" -> mineOres = b;
                    case "turnSpeed" -> turnSpeed = Math.max(1, Math.min(10, Integer.parseInt(v)));
                    case "solver" -> solver = b;
                    case "solverAutoClick" -> solverAutoClick = b;
                    case "solverRightClick" -> solverRightClick = b;
                    case "esp" -> esp = b;
                    case "tracers" -> tracers = b;
                    case "hud" -> hud = b;
                    case "hudCorner" -> hudCorner = Math.floorMod(Integer.parseInt(v), 4);
                    default -> { }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
