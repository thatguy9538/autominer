package com.example.ghostbot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ghost Bot: kills the Ghosts (invisible charged creepers) in The Mist under the Dwarven Mines.
 * Finds them, turns to them like a person would, walks or path-finds over, and attacks; roams the
 * area when nothing is around. Fighting is adapted from V5 (GPL-3.0).
 */
public class GhostBotClient implements ClientModInitializer {

    public static final String MOD_ID = "ghostbot";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    // settings (config/ghostbot.txt)
    public static int cps = 10;
    public static int turnSpeed = 400;          // degrees per second (V5's default)
    public static int pathThreshold = 15;       // walk straight in closer than this, path-find further
    public static int leash = 30;               // only fight within this many blocks of where you started
    public static boolean rightClick = false;
    /** true: fight anywhere in The Mist; false: only within the leash of the start spot. */
    public static boolean mistArea = true;
    public static boolean hud = true;

    // run state
    public static boolean running = false;
    private static Vec3d leashCenter = null;
    private static long startedAt = 0;
    private static int pauseTicks = 0, areaTicks = 0;
    private static boolean warnedArea = false;
    private static long coins = 0;
    private static final java.util.LinkedHashMap<String, Integer> drops = new java.util.LinkedHashMap<>();
    private static final String[] DROP_NAMES = {"Sorrow", "Volta", "Plasma", "Ghostly Boots", "Bag of Cash"};
    private static final java.util.regex.Pattern COINS = java.util.regex.Pattern.compile("materialized ([\\d,]+) coins");
    private static Vec3d lastPos = null;
    private static float lastYaw = Float.NaN, lastPitch = Float.NaN;
    private static String status = "Off";

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ghostbot.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        ClientTickEvents.END_CLIENT_TICK.register(GhostBotClient::tick);
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) onChat(Formatting.strip(msg.getString()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> stop(c, null));
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), GhostBotClient::renderHud);
        ClientCommandRegistrationCallback.EVENT.register((d, a) -> {
            for (String root : new String[]{"ghostbot", "gb"}) d.register(ClientCommandManager.literal(root)
                    .executes(c -> {
                        help(MinecraftClient.getInstance());
                        return 1;
                    })
                    .then(ClientCommandManager.literal("start").executes(c -> {
                        start(MinecraftClient.getInstance());
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stop").executes(c -> {
                        stop(MinecraftClient.getInstance(), "Stopped.");
                        return 1;
                    }))
                    .then(setting(root, "cps", 5, 15, v -> cps = v, () -> cps + " clicks per second"))
                    .then(setting(root, "turn", 100, 800, v -> turnSpeed = v, () -> turnSpeed + " degrees per second"))
                    .then(setting(root, "leash", 10, 80, v -> leash = v, () -> leash + " blocks from the start spot"))
                    .then(setting(root, "path", 5, 30, v -> pathThreshold = v, () -> "path-find past " + pathThreshold + " blocks"))
                    .then(ClientCommandManager.literal("here").executes(c -> {
                        ClientPlayerEntity p = MinecraftClient.getInstance().player;
                        if (p != null) leashCenter = new Vec3d(p.getX(), p.getY(), p.getZ());
                        say(MinecraftClient.getInstance(), "Leash centered here.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("area").executes(c -> {
                        mistArea = !mistArea;
                        save();
                        say(MinecraftClient.getInstance(), mistArea ? "Area: all of The Mist (follows the sidebar)."
                                : "Area: within " + leash + " blocks of where you started (/gb leash, /gb here).");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("button").executes(c -> {
                        rightClick = !rightClick;
                        save();
                        say(MinecraftClient.getInstance(), "Attack with: " + (rightClick ? "right click (ability weapons)" : "left click"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("ability")
                            .executes(c -> {
                                say(MinecraftClient.getInstance(), "Ability item: " + Ability.describe() + ", every " + Ability.cooldownMs
                                        + " ms, within " + Ability.range + " blocks.");
                                say(MinecraftClient.getInstance(), "/" + root + " ability set (hold the item) | clear | cooldown <ms>"
                                        + " | when <always|lowhp|group> | group <2-10> | hp <10-90> | range <2-20>");
                                return 1;
                            })
                            .then(ClientCommandManager.literal("set").executes(c -> {
                                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                                String n = p == null ? null : Ability.setFromHand(p);
                                if (n == null) say(MinecraftClient.getInstance(), "Hold the item first, then run this again.");
                                else {
                                    save();
                                    say(MinecraftClient.getInstance(), "Ability item: " + n + ". Keep it in your hotbar; I'll switch to it,"
                                            + " right-click and switch back.");
                                }
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("clear").executes(c -> {
                                Ability.item = "";
                                save();
                                say(MinecraftClient.getInstance(), "No ability item.");
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("when").then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                    .suggests((ctx, b) -> {
                                        b.suggest("always");
                                        b.suggest("lowhp");
                                        b.suggest("group");
                                        return b.buildFuture();
                                    })
                                    .executes(c -> {
                                        String m = StringArgumentType.getString(c, "mode").toUpperCase(Locale.ROOT);
                                        try {
                                            Ability.when = Ability.When.valueOf(m.equals("LOW") ? "LOWHP" : m);
                                            save();
                                            say(MinecraftClient.getInstance(), "Ability item: " + Ability.describe());
                                        } catch (IllegalArgumentException e) {
                                            say(MinecraftClient.getInstance(), "Use always, lowhp or group.");
                                        }
                                        return 1;
                                    })))
                            .then(setting(root + " ability", "cooldown", 100, 120000, v -> Ability.cooldownMs = v, () -> Ability.cooldownMs + " ms between uses"))
                            .then(setting(root + " ability", "hp", 10, 90, v -> Ability.hpPercent = v, () -> "use below " + Ability.hpPercent + "% health (when lowhp)"))
                            .then(setting(root + " ability", "group", 2, 10, v -> Ability.group = v, () -> "use when " + Ability.group + "+ ghosts are within range (when group)"))
                            .then(setting(root + " ability", "range", 2, 20, v -> Ability.range = v, () -> "use when a target is within " + Ability.range + " blocks")))
                    .then(ClientCommandManager.literal("hud").executes(c -> {
                        hud = !hud;
                        save();
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stats").executes(c -> {
                        stats(MinecraftClient.getInstance());
                        return 1;
                    })));
        });
    }

    private interface IntSetter {
        void set(int v);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
    setting(String root, String name, int min, int max, IntSetter set, java.util.function.Supplier<String> show) {
        return ClientCommandManager.literal(name)
                .executes(c -> {
                    say(MinecraftClient.getInstance(), name + ": " + show.get() + "  (/" + root + " " + name + " <" + min + "-" + max + ">)");
                    return 1;
                })
                .then(ClientCommandManager.argument("value", IntegerArgumentType.integer(min, max)).executes(c -> {
                    set.set(IntegerArgumentType.getInteger(c, "value"));
                    save();
                    say(MinecraftClient.getInstance(), name + ": " + show.get());
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ the loop

    private static void tick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            if (running) stop(client, "Stopped.");
            else start(client);
        }
        if (!running || client.player == null || client.world == null) return;
        ClientPlayerEntity p = client.player;

        // failsafes: a big jump in position or rotation we didn't cause (a teleport or a staff check)
        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
        if (lastPos != null && pos.distanceTo(lastPos) > 8) {
            stop(client, "Stopped: you were teleported " + Math.round(pos.distanceTo(lastPos)) + " blocks. Check what happened.");
            alarm(p);
            return;
        }
        if (!Float.isNaN(lastYaw) && (Math.abs(MathHelper.wrapDegrees(p.getYaw() - lastYaw)) > 60 || Math.abs(p.getPitch() - lastPitch) > 45)) {
            stop(client, "Stopped: your camera was turned by something else (a staff check, or you moved the mouse).");
            alarm(p);
            return;
        }
        lastPos = pos;

        // pause (don't fight) while a menu is open
        if (client.currentScreen != null) {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
                Combat.stopMoving(client);
                status = "Paused (menu open)";
            }
            remember(p);
            return;
        }
        if (pauseTicks > 0) {
            pauseTicks--;
            Combat.stopMoving(client);
            remember(p);
            return;
        }
        if (!warnedArea && ++areaTicks == 40) {
            warnedArea = true;
            if (!Area.sidebarSaysMist(client)) say(client, mistArea ? "Heads up: the sidebar doesn't say you're in The Mist. Go down there first;"
                    + " ghosts only spawn in The Mist." : "Heads up: the sidebar doesn't say you're in The Mist. Ghosts only spawn there.");
        }

        Area.tick(client);
        Combat.tick(client, false);
        Ability.tick(client, false);
        status = mistArea && Area.left ? "Walked out of The Mist - going back"
                : Combat.target != null ? "Fighting a ghost"
                : Combat.state == Combat.State.PATHING ? "Looking for ghosts" : "Waiting for spawns";
        remember(p);
    }

    private static void remember(ClientPlayerEntity p) {
        lastYaw = p.getYaw();
        lastPitch = p.getPitch();
    }

    private static void onChat(String s) {
        if (s == null || !running) return;
        String l = s.toLowerCase(Locale.ROOT);
        MinecraftClient client = MinecraftClient.getInstance();
        Ability.onChat(l);
        if (l.contains("you died") || l.startsWith("☠ you") || l.contains("you were killed")) {
            stop(client, "Stopped: you died.");
            return;
        }
        java.util.regex.Matcher m = COINS.matcher(l);
        if (m.find()) {
            try {
                coins += Long.parseLong(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        if (l.contains("drop!") || l.contains("rare drop") || l.contains("you found")) {
            for (String d : DROP_NAMES) {
                if (l.contains(d.toLowerCase(Locale.ROOT))) {
                    drops.merge(d, 1, Integer::sum);
                    say(client, "Drop: " + d + " (" + drops.get(d) + " this run)");
                }
            }
        }
    }

    public static void start(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || running) return;
        running = true;
        leashCenter = new Vec3d(p.getX(), p.getY(), p.getZ());
        startedAt = System.currentTimeMillis();
        Combat.kills = 0;
        coins = 0;
        drops.clear();
        lastPos = null;
        lastYaw = Float.NaN;
        areaTicks = 0;
        warnedArea = false;
        Area.reset();
        say(client, mistArea ? "Started: killing ghosts anywhere in The Mist." : "Started: killing ghosts within " + leash + " blocks of here.");
    }

    public static void stop(MinecraftClient client, String why) {
        if (!running) return;
        running = false;
        Ability.reset(client);
        Combat.reset(client);
        if (Combat.kills > 0) stats(client);
        status = "Off";
        if (why != null) say(client, why);
    }

    public static Vec3d leashCenter() {
        return leashCenter;
    }

    /** Where ghosts may be fought: anywhere in The Mist, or inside the leash. */
    public static boolean inArea(Vec3d pos) {
        return mistArea ? Area.allowed(pos) : inLeash(pos);
    }

    public static boolean inLeash(Vec3d pos) {
        if (leashCenter == null) return true;
        double dx = pos.x - leashCenter.x, dz = pos.z - leashCenter.z;
        return dx * dx + dz * dz <= (double) leash * leash;
    }

    private static void alarm(ClientPlayerEntity p) {
        for (int i = 0; i < 3; i++) p.playSound(SoundEvents.BLOCK_ANVIL_LAND, 1f, 1f);
    }

    // ------------------------------------------------------------------ HUD

    private static void renderHud(DrawContext ctx, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!hud || !running || mc.player == null || mc.options.hudHidden) return;
        List<String> lines = new ArrayList<>();
        lines.add("Ghost Bot");
        lines.add(status);
        lines.add("Target: " + Combat.describe(mc) + "  (" + Combat.found + " found)");
        if (mistArea) lines.add(Area.describe());
        if (!Ability.item.isEmpty()) lines.add("Ability: " + Ability.describe());
        double hours = Math.max(1.0 / 60, (System.currentTimeMillis() - startedAt) / 3600000.0);
        lines.add("Kills: " + Combat.kills + "  (" + Math.round(Combat.kills / hours) + "/h)");
        if (coins > 0) lines.add("Coins dropped: " + String.format("%,d", coins));
        if (!drops.isEmpty()) {
            StringBuilder sb = new StringBuilder("Drops:");
            drops.forEach((k, v) -> sb.append(' ').append(k).append(" x").append(v));
            lines.add(sb.toString());
        }
        int w = 0;
        for (String l : lines) w = Math.max(w, mc.textRenderer.getWidth(l));
        ctx.fill(2, 2, w + 10, lines.size() * 10 + 6, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(mc.textRenderer, lines.get(i), 6, 5 + i * 10, i == 0 ? 0xFF7FD4FF : 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ chat + settings

    public static void say(MinecraftClient client, String msg) {
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("[Ghost Bot] ").formatted(Formatting.AQUA)
                .append(Text.literal(msg).formatted(Formatting.WHITE)), false);
    }

    private static void help(MinecraftClient c) {
        say(c, (running ? "Running: " + status : "Off") + ". " + cps + " CPS, turn " + turnSpeed + "/s, leash " + leash
                + ", attack with " + (rightClick ? "right" : "left") + " click");
        say(c, "/gb start | stop | stats | area | cps <5-15> | turn <100-800> | leash <10-80> | here"
                + " | path <5-30> | button | ability | hud");
    }

    private static void stats(MinecraftClient c) {
        double mins = Math.max(1, (System.currentTimeMillis() - startedAt) / 60000.0);
        StringBuilder sb = new StringBuilder("Ghosts killed: " + Combat.kills + " in " + Math.round(mins) + " min ("
                + Math.round(Combat.kills * 60 / mins) + "/h)");
        if (coins > 0) sb.append(", coins dropped: ").append(String.format("%,d", coins));
        drops.forEach((k, v) -> sb.append(", ").append(k).append(" x").append(v));
        say(c, sb.toString());
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ghostbot.txt");
    }

    private static void load() {
        try {
            if (!Files.exists(file())) {
                save();
                return;
            }
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "cps" -> cps = Math.max(5, Math.min(15, Integer.parseInt(v)));
                        case "turnSpeed" -> turnSpeed = Math.max(100, Math.min(800, Integer.parseInt(v)));
                        case "pathThreshold" -> pathThreshold = Math.max(5, Math.min(30, Integer.parseInt(v)));
                        case "leash" -> leash = Math.max(10, Math.min(80, Integer.parseInt(v)));
                        case "rightClick" -> rightClick = v.equals("true");
                        case "mistArea" -> mistArea = v.equals("true");
                        case "hud" -> hud = v.equals("true");
                        case "abilityItem" -> Ability.item = v;
                        case "abilityCooldownMs" -> Ability.cooldownMs = Math.max(100, Math.min(120000, Integer.parseInt(v)));
                        case "abilityWhen" -> Ability.when = Ability.When.valueOf(v);
                        case "abilityHp" -> Ability.hpPercent = Math.max(10, Math.min(90, Integer.parseInt(v)));
                        case "abilityGroup" -> Ability.group = Math.max(2, Math.min(10, Integer.parseInt(v)));
                        case "abilityRange" -> Ability.range = Math.max(2, Math.min(20, Integer.parseInt(v)));
                        default -> { }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read settings", e);
        }
    }

    static void save() {
        try {
            Files.writeString(file(), "# Ghost Bot settings\ncps=" + cps
                    + "\nturnSpeed=" + turnSpeed + "\npathThreshold=" + pathThreshold + "\nleash=" + leash
                    + "\nrightClick=" + rightClick + "\n# true = anywhere in The Mist, false = within the leash\nmistArea=" + mistArea
                    + "\nhud=" + hud + "\n# right-click ability item (name, without colors; empty = none)\nabilityItem=" + Ability.item
                    + "\nabilityCooldownMs=" + Ability.cooldownMs + "\n# ALWAYS, LOWHP or GROUP\nabilityWhen=" + Ability.when + "\nabilityGroup=" + Ability.group
                    + "\nabilityHp=" + Ability.hpPercent + "\nabilityRange=" + Ability.range + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Couldn't save settings", e);
        }
    }
}
