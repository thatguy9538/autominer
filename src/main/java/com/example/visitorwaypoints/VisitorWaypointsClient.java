package com.example.visitorwaypoints;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visitor Waypoints: open your Visitor's Logbook and this marks where to meet the visitors you
 * haven't unlocked yet. Only visitors your Garden level allows, and only on the island you're on.
 * /vw for status and settings.
 */
public class VisitorWaypointsClient implements ClientModInitializer {

    public static final String MOD_ID = "visitorwaypoints";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static boolean enabled = true;
    public static boolean tracers = true;
    /** Your Garden level (-1 = not known yet). */
    public static int gardenLevel = -1;

    /** A waypoint being drawn right now. */
    /** A waypoint being drawn right now; `spotted` = we saw the NPC there ourselves (not just the wiki). */
    public record Shown(Visitors.Visitor visitor, String zone, Vec3d pos, boolean spotted) {}

    public static List<Shown> shown = List.of();

    private static KeyBinding toggleKey;
    private static int ticks = 0;
    private static final Pattern NPC_CHAT = Pattern.compile("^\\[NPC\\]\\s*([^:]+):");

    @Override
    public void onInitializeClient() {
        Visitors.load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.visitorwaypoints.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        ClientTickEvents.END_CLIENT_TICK.register(VisitorWaypointsClient::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String s = Formatting.strip(message.getString());
            if (s == null || overlay) return;
            Where.onText(s);
            Matcher m = NPC_CHAT.matcher(s.trim());
            if (m.find()) onNpcTalk(m.group(1).trim());
        });
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "waypoints"), Render::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            for (String root : new String[]{"vw", "visitorwaypoints"}) {
                dispatcher.register(ClientCommandManager.literal(root)
                        .executes(c -> {
                            status(MinecraftClient.getInstance());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("toggle").executes(c -> {
                            enabled = !enabled;
                            Visitors.save();
                            say(MinecraftClient.getInstance(), "Waypoints " + (enabled ? "ON" : "OFF"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("tracer").executes(c -> {
                            tracers = !tracers;
                            Visitors.save();
                            say(MinecraftClient.getInstance(), "Tracer line " + (tracers ? "ON" : "OFF"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("level")
                                .then(ClientCommandManager.argument("level", IntegerArgumentType.integer(0, 30))
                                        .executes(c -> {
                                            gardenLevel = IntegerArgumentType.getInteger(c, "level");
                                            Visitors.save();
                                            say(MinecraftClient.getInstance(), "Garden level set to " + gardenLevel);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("list").executes(c -> {
                            list(MinecraftClient.getInstance());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("forgetspots").executes(c -> {
                            Visitors.FOUND.clear();
                            Visitors.save();
                            say(MinecraftClient.getInstance(), "Forgot every NPC spot found by searching. Back to wiki spots.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(c -> {
                            Visitors.reset();
                            say(MinecraftClient.getInstance(), "Forgot everything read from the logbook. Open it again.");
                            return 1;
                        })));
            }
        });
    }

    private static void onTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            Visitors.save();
            say(client, "Waypoints " + (enabled ? "ON" : "OFF"));
        }
        if (client.player == null || client.world == null) {
            shown = List.of();
            return;
        }
        Logbook.tick(client);
        Where.tick(client);
        Scanner.tick(client);
        if (++ticks % 10 == 0) {
            scanMenuForGardenLevel(client);
            shown = compute();
        }
    }

    /** Any open menu with an item like "Garden Level XII" tells us the level (the Desk, SkyBlock menu...). */
    private static void scanMenuForGardenLevel(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?>)) return;
        for (Slot s : client.player.currentScreenHandler.slots) {
            if (s.inventory == client.player.getInventory() || !s.hasStack()) continue;
            String n = Formatting.strip(s.getStack().getName().getString());
            if (n != null && n.toLowerCase(Locale.ROOT).contains("garden level")) Where.onText(n);
        }
    }

    /** Which waypoints to draw: not unlocked (per the logbook), Garden level OK, on this island. */
    private static List<Shown> compute() {
        List<Shown> out = new ArrayList<>();
        if (!enabled || Where.island == null) return out;
        for (Visitors.Visitor v : Visitors.ALL) {
            if (!Scanner.needsTrip(v)) continue;
            Visitors.State st = Visitors.STATE.get(v.name());
            if (st == null || !st.seen || st.unlocked || st.talked) continue;
            if (!levelOk(v, st)) continue;
            // spots we found by searching the island win; the wiki's spots are the fallback
            List<Visitors.Spot> found = Scanner.spots(v, Where.island);
            if (!found.isEmpty()) {
                for (Visitors.Spot s : found) out.add(new Shown(v, "spotted", new Vec3d(s.x, s.y, s.z), true));
                continue;
            }
            for (Visitors.Waypoint w : v.waypoints()) {
                if (!w.island().equalsIgnoreCase(Where.island)) continue;
                out.add(new Shown(v, w.zone(), new Vec3d(w.x(), w.y(), w.z()), false));
            }
        }
        return out;
    }

    /** Garden level check: the logbook's own ✔/✖ if it showed one, otherwise your level vs the wiki's. */
    static boolean levelOk(Visitors.Visitor v, Visitors.State st) {
        if (st != null && st.levelOk != null) return st.levelOk;
        if (v.gardenLevel() <= 1) return true;
        return gardenLevel >= 0 && gardenLevel >= v.gardenLevel();
    }

    /** "[NPC] Alchemist: ..." in chat: you've met them, so hide their "Talk to" waypoint. */
    private static void onNpcTalk(String npc) {
        Visitors.Visitor v = Visitors.find(npc);
        if (v == null || !v.requirement().toLowerCase(Locale.ROOT).startsWith("talk to")) return;
        Visitors.State st = Visitors.STATE.get(v.name());
        if (st == null || st.unlocked || st.talked) return;
        st.talked = true;
        Visitors.save();
        say(MinecraftClient.getInstance(), "Met " + v.name() + "! They can visit your Garden now.");
    }

    public static void setGardenLevel(int level) {
        if (level <= 0 || level > 30 || level == gardenLevel) return;
        gardenLevel = level;
        Visitors.save();
    }

    // ------------------------------------------------------------------ chat output

    public static void say(MinecraftClient client, String msg) {
        if (client.player == null) return;
        client.player.sendMessage(prefix().append(Text.literal(msg).formatted(Formatting.WHITE)), false);
    }

    private static MutableText prefix() {
        return Text.literal("[Visitor Waypoints] ").formatted(Formatting.GOLD);
    }

    private static void status(MinecraftClient client) {
        int seen = 0, locked = 0;
        for (Visitors.State st : Visitors.STATE.values()) {
            if (st.seen) seen++;
            if (st.seen && !st.unlocked && !st.talked) locked++;
        }
        say(client, "Waypoints " + (enabled ? "ON" : "OFF") + ", tracer " + (tracers ? "ON" : "OFF"));
        say(client, "Garden level: " + (gardenLevel < 0 ? "unknown (open your Garden Desk, or /vw level <n>)" : gardenLevel));
        say(client, "Island: " + (Where.island == null ? "unknown" : Where.island + " (from " + Where.islandSource + ")"));
        say(client, "Logbook: " + seen + " visitors read, " + locked + " not unlocked; " + shown.size() + " waypoints here");
        if (seen == 0) say(client, "Open the Visitor's Logbook in your Garden and go through its pages to start.");
        int spots = 0;
        for (List<Visitors.Spot> l : Visitors.FOUND.values()) spots += l.size();
        say(client, "Island search: " + spots + " NPC spots found so far (they replace the wiki's spots)");
        say(client, "/vw list, /vw toggle, /vw tracer, /vw level <n>, /vw forgetspots, /vw reset");
    }

    private static void list(MinecraftClient client) {
        if (Where.island == null) {
            say(client, "I don't know which island you're on yet.");
            return;
        }
        if (shown.isEmpty()) {
            say(client, "No visitors to unlock on " + Where.island + ".");
            return;
        }
        say(client, "To unlock on " + Where.island + ":");
        for (Shown s : shown) {
            Visitors.Visitor v = s.visitor();
            client.player.sendMessage(Text.literal(" - " + v.name()).formatted(Formatting.YELLOW)
                    .append(Text.literal("  " + v.requirement() + "  @ " + (int) Math.floor(s.pos().x) + ", "
                            + (int) Math.floor(s.pos().y) + ", " + (int) Math.floor(s.pos().z)
                            + (s.spotted() ? " (spotted)" : " (wiki: " + s.zone() + ")"))
                            .formatted(Formatting.GRAY)), false);
        }
    }
}
