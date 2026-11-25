package com.example.rankscout;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Rank Scout: on Fakepixel, scans the profiles of players around you and keeps, per skill and
 * collection, the ones ahead of you. Writes .minecraft/rankscout/ranks.html. Starts by itself on join.
 */
public class RankScoutClient implements ClientModInitializer {

    public static final String MOD_ID = "rankscout";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    // settings (rankscout/settings.txt)
    public static boolean enabled = true;
    public static boolean onlyWhenIdle = true;
    public static int secondsBetween = 8;
    public static int rescanHours = 12;
    public static String command = "viewprofile";
    public static String serverMatch = "fakepixel";

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        loadSettings();
        Store.load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rankscout.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        // start by itself every time you join Fakepixel
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            if (enabled && onFakepixel(client)) Scanner.start(client);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Scanner.stop());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) toggle(client);
            Scanner.tick(client);
        });

        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(ClientCommandManager.literal("ranks")
                .executes(c -> {
                    status(MinecraftClient.getInstance());
                    return 1;
                })
                .then(ClientCommandManager.literal("open").executes(c -> {
                    Store.save();
                    Util.getOperatingSystem().open(Store.html().toFile());
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(c -> {
                    toggle(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("scan").then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(c -> {
                            Scanner.queue(StringArgumentType.getString(c, "player"));
                            if (!Scanner.running) Scanner.start(MinecraftClient.getInstance());
                            say(MinecraftClient.getInstance(), "Queued " + StringArgumentType.getString(c, "player") + ".");
                            return 1;
                        })))
                .then(ClientCommandManager.literal("me").executes(c -> {
                    if (Store.data.me != null) Store.data.me.scanned = 0;
                    say(MinecraftClient.getInstance(), "Re-reading your own profile next.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("dump").executes(c -> {
                    Scanner.dumpNext();
                    say(MinecraftClient.getInstance(), "The next profile's menus will be saved to rankscout/menu-dump.txt.");
                    return 1;
                }))));
    }

    static boolean onFakepixel(MinecraftClient client) {
        ServerInfo s = client.getCurrentServerEntry();
        return s != null && s.address != null && s.address.toLowerCase(Locale.ROOT).contains(serverMatch);
    }

    private static void toggle(MinecraftClient client) {
        enabled = !enabled;
        saveSettings();
        if (!enabled) Scanner.stop();
        else if (onFakepixel(client)) Scanner.start(client);
        say(client, "Scanning " + (enabled ? "ON" : "OFF"));
    }

    private static void status(MinecraftClient client) {
        say(client, "Scanning " + (enabled ? "ON" : "OFF") + (Scanner.running ? " - " + Scanner.status : ""));
        say(client, Store.data.players.size() + " players ahead of you saved, " + Store.data.lastScan.size()
                + " profiles scanned. Your profile: " + (Store.data.me == null ? "not read yet"
                : Store.data.me.skills.size() + " skills, " + Store.data.me.collections.size() + " collections"));
        say(client, "/ranks open, /ranks toggle, /ranks scan <player>, /ranks me, /ranks dump");
        say(client, "Report: " + Store.html());
    }

    public static void say(MinecraftClient client, String msg) {
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("[Rank Scout] ").formatted(Formatting.GREEN)
                .append(Text.literal(msg).formatted(Formatting.WHITE)), false);
    }

    // ------------------------------------------------------------------ settings

    private static Path settingsFile() {
        return FabricLoader.getInstance().getGameDir().resolve("rankscout").resolve("settings.txt");
    }

    private static void loadSettings() {
        try {
            if (!Files.exists(settingsFile())) {
                saveSettings();
                return;
            }
            for (String line : Files.readAllLines(settingsFile(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                switch (k) {
                    case "enabled" -> enabled = v.equals("true");
                    case "onlyWhenIdle" -> onlyWhenIdle = v.equals("true");
                    case "secondsBetween" -> secondsBetween = Math.max(3, Integer.parseInt(v));
                    case "rescanHours" -> rescanHours = Math.max(1, Integer.parseInt(v));
                    case "command" -> command = v.replaceFirst("^/", "");
                    case "server" -> serverMatch = v.toLowerCase(Locale.ROOT);
                    default -> { }
                }
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read settings", e);
        }
    }

    static void saveSettings() {
        try {
            Files.createDirectories(settingsFile().getParent());
            Files.writeString(settingsFile(), "# Rank Scout settings\nenabled=" + enabled
                    + "\n# only scan while you stand still (the profile menu opens on your screen)\nonlyWhenIdle=" + onlyWhenIdle
                    + "\n# seconds to wait between profiles (min 3)\nsecondsBetween=" + secondsBetween
                    + "\n# hours before the same player is scanned again\nrescanHours=" + rescanHours
                    + "\n# command that opens someone's profile (without /)\ncommand=" + command
                    + "\n# start automatically on servers whose address contains this\nserver=" + serverMatch + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Couldn't save settings", e);
        }
    }
}
