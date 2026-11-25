package com.example.rankscout;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Formatting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Scans profiles one at a time: /viewprofile <name>, read the menu, follow Skills / Collections
 * buttons a couple of pages deep, close. Waits while you're busy (moving, in a menu) and between profiles.
 */
public final class Scanner {

    private Scanner() {
    }

    public static boolean running = false;
    public static String status = "Not on Fakepixel";

    private enum Phase { IDLE, WAIT_OPEN, WAIT_PAGE }

    private static Phase phase = Phase.IDLE;
    private static String target = null;
    private static boolean targetIsMe = false;
    private static int timer = 0, settle = 0, clicks = 0, cooldown = 0, idleTicks = 0;
    private static String pageSig = "";
    private static Map<String, Parse.Stat> skills, collections;
    private static final Deque<Integer> toVisit = new ArrayDeque<>();   // slot ids of buttons on the current page
    private static final Set<String> visitedButtons = new HashSet<>();
    private static final Deque<String> manual = new ArrayDeque<>();
    private static double lx, ly, lz;
    private static float lyaw, lpitch;
    private static boolean dumpNext = false;

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final int MAX_CLICKS = 14;
    /** Most profiles read per player (Fakepixel's Select Profile menu lists each one). */
    private static final int MAX_PROFILES = 4;
    /** Slot ids of the "View player profile" buttons, and which one we're on. */
    private static List<Integer> profileSlots = null;
    private static int profileIdx = 0;

    public static void start(MinecraftClient client) {
        if (running) return;
        running = true;
        cooldown = 100;                                        // let the server settle after joining
        status = "Starting";
        RankScoutClient.say(client, "Scanning players ahead of you. Report: /ranks open");
    }

    public static void stop() {
        running = false;
        phase = Phase.IDLE;
        status = "Stopped";
    }

    public static void queue(String name) {
        manual.addLast(name);
    }

    public static void dumpNext() {
        dumpNext = true;
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (!running || p == null || client.world == null) return;
        trackIdle(p, client);

        if (phase != Phase.IDLE) {
            work(client);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (RankScoutClient.onlyWhenIdle && idleTicks < 100 && manual.isEmpty()) {
            status = "Waiting for you to stand still";
            return;
        }
        if (client.currentScreen != null) return;

        // your own profile first, then every 30 minutes
        String myName = p.getGameProfile().name();
        String next;
        if (Store.data.me == null || System.currentTimeMillis() - Store.data.me.scanned > 30 * 60_000L) {
            next = myName;
        } else if (!manual.isEmpty()) {
            next = manual.pollFirst();
        } else {
            next = pickPlayer(client, myName);
        }
        if (next == null) {
            status = "Everyone here was scanned recently";
            cooldown = 200;
            return;
        }
        begin(client, next, next.equalsIgnoreCase(myName));
    }

    private static void begin(MinecraftClient client, String name, boolean me) {
        target = name;
        targetIsMe = me;
        skills = new LinkedHashMap<>();
        collections = new LinkedHashMap<>();
        toVisit.clear();
        visitedButtons.clear();
        profileSlots = null;
        profileIdx = 0;
        clicks = 0;
        settle = 0;
        timer = 80;
        pageSig = "";
        phase = Phase.WAIT_OPEN;
        status = "Scanning " + name;
        client.player.networkHandler.sendChatCommand(RankScoutClient.command + " " + name);
    }

    private static void work(MinecraftClient client) {
        if (--timer <= 0) {
            if (profileSlots != null) nextProfileOrFinish(client);
            else finish(client, true);
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            if (phase == Phase.WAIT_PAGE) finish(client, false);   // you closed it, or it closed itself
            return;
        }
        String sig = sig(client);
        if (sig.isEmpty()) return;
        if (phase == Phase.WAIT_PAGE && sig.equals(pageSig)) return;      // old page still showing
        if (++settle < 4) return;                                          // let the whole page arrive

        // read this page
        pageSig = sig;
        List<Slot> slots = container(client);
        if (dumpNext) dump(client, slots);

        // Fakepixel: /viewprofile first shows "Select Profile" with a button per profile: open them one by one
        List<Integer> profiles = profileButtons(slots);
        if (!profiles.isEmpty()) {
            if (profileSlots == null) profileSlots = profiles;
            if (profileIdx < profileSlots.size()) {
                clickSlot(client, profileSlots.get(profileIdx));
                return;
            }
            finish(client, false);
            return;
        }
        for (Slot s : slots) Parse.item(s.getStack(), skills, collections);

        // queue the Skills / Collections buttons on this page we haven't clicked yet
        toVisit.clear();
        for (Slot s : slots) {
            String key = Parse.name(s.getStack());
            if (Parse.isStatsButton(s.getStack()) && visitedButtons.add(key.toLowerCase(Locale.ROOT))) toVisit.add(s.id);
        }
        nextButton(client);
    }

    private static void nextButton(MinecraftClient client) {
        if (toVisit.isEmpty() || clicks >= MAX_CLICKS) {
            // go back a page if this was a sub page and other buttons may wait there, else we're done
            Slot back = byName(client, "go back");
            if (back != null && clicks < MAX_CLICKS && clicks > 0 && phase == Phase.WAIT_PAGE && hasUnvisitedAfterBack()) {
                clickSlot(client, back.id);
                return;
            }
            nextProfileOrFinish(client);
            return;
        }
        clickSlot(client, toVisit.pollFirst());
    }

    /** Done with one profile: open the player's next profile (best value per stat is kept), or finish. */
    private static void nextProfileOrFinish(MinecraftClient client) {
        if (profileSlots != null && profileIdx + 1 < Math.min(profileSlots.size(), MAX_PROFILES)) {
            profileIdx++;
            if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
            toVisit.clear();
            visitedButtons.clear();
            clicks = 0;
            settle = 0;
            timer = 80;
            pageSig = "";
            phase = Phase.WAIT_OPEN;
            client.player.networkHandler.sendChatCommand(RankScoutClient.command + " " + target);
            return;
        }
        finish(client, false);
    }

    /** "View player profile" buttons (lore "Profile: Pineapple", "Click to view!"). */
    private static List<Integer> profileButtons(List<Slot> slots) {
        List<Integer> out = new ArrayList<>();
        for (Slot sl : slots) {
            String n = Parse.name(sl.getStack()).toLowerCase(Locale.ROOT);
            String lore = String.join(" ", Parse.lore(sl.getStack())).toLowerCase(Locale.ROOT);
            if (n.contains("view player profile") || (lore.contains("profile:") && lore.contains("click to view"))) {
                out.add(sl.id);
            }
        }
        return out;
    }

    private static boolean hasUnvisitedAfterBack() {
        return false;   // buttons are followed depth-first as they're found; keep scans short
    }

    private static void clickSlot(MinecraftClient client, int slotId) {
        clicks++;
        client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slotId, 0, SlotActionType.PICKUP, client.player);
        phase = Phase.WAIT_PAGE;
        settle = 0;
        timer = 60;
    }

    private static void finish(MinecraftClient client, boolean timedOut) {
        if (client.currentScreen instanceof HandledScreen<?> && client.player != null) client.player.closeHandledScreen();
        phase = Phase.IDLE;
        cooldown = RankScoutClient.secondsBetween * 20;
        dumpNext = false;
        int found = skills.size() + collections.size();
        Store.Profile prof = new Store.Profile();
        prof.name = target;
        prof.scanned = System.currentTimeMillis();
        prof.skills = skills;
        prof.collections = collections;
        if (targetIsMe) {
            if (found == 0) {
                status = "Couldn't read your own profile";
                RankScoutClient.say(client, "Couldn't read any skills or collections from your profile"
                        + (timedOut ? " (it didn't open)" : "") + ". Try /ranks dump, then send me rankscout/menu-dump.txt.");
                cooldown = 20 * 120;
                Store.data.lastScan.put(target.toLowerCase(), prof.scanned);
                return;
            }
            Store.setMe(prof);
            status = "Read your profile: " + skills.size() + " skills, " + collections.size() + " collections";
            return;
        }
        int kept = Store.addPlayer(prof);
        status = target + ": " + (found == 0 ? "nothing readable" : kept + " stats ahead of you");
    }

    /** Someone in your lobby we haven't looked at in a while (closest first). */
    private static String pickPlayer(MinecraftClient client, String myName) {
        long cut = System.currentTimeMillis() - RankScoutClient.rescanHours * 3_600_000L;
        Set<String> names = new LinkedHashSet<>();
        List<PlayerEntity> near = new ArrayList<>(client.world.getPlayers());
        near.sort((a, b) -> Double.compare(a.squaredDistanceTo(client.player), b.squaredDistanceTo(client.player)));
        for (PlayerEntity pe : near) addIfReal(names, pe.getGameProfile().name(), pe.getUuid());
        if (client.getNetworkHandler() != null) {
            for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
                addIfReal(names, e.getProfile().name(), e.getProfile().id());
            }
        }
        for (String n : names) {
            if (n.equalsIgnoreCase(myName)) continue;
            if (Store.lastScan(n) < cut) return n;
        }
        return null;
    }

    /** Real players only: proper names, player UUIDs (v4 online / v3 offline), not NPCs or tab-list fillers. */
    private static void addIfReal(Set<String> out, String name, UUID id) {
        if (name == null || id == null || !NAME.matcher(name).matches()) return;
        int v = id.version();
        if (v != 4 && v != 3) return;
        out.add(name);
    }

    private static void trackIdle(ClientPlayerEntity p, MinecraftClient client) {
        boolean moved = Math.abs(p.getX() - lx) + Math.abs(p.getY() - ly) + Math.abs(p.getZ() - lz) > 0.05
                || Math.abs(p.getYaw() - lyaw) + Math.abs(p.getPitch() - lpitch) > 1
                || client.options.attackKey.isPressed() || client.options.useKey.isPressed();
        boolean ourMenu = phase != Phase.IDLE;
        if (moved || (client.currentScreen != null && !ourMenu)) idleTicks = 0;
        else idleTicks++;
        lx = p.getX();
        ly = p.getY();
        lz = p.getZ();
        lyaw = p.getYaw();
        lpitch = p.getPitch();
    }

    private static List<Slot> container(MinecraftClient client) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : client.player.currentScreenHandler.slots) {
            if (s.inventory != client.player.getInventory() && s.hasStack()) out.add(s);
        }
        return out;
    }

    private static Slot byName(MinecraftClient client, String words) {
        for (Slot s : container(client)) if (Parse.name(s.getStack()).toLowerCase(Locale.ROOT).contains(words)) return s;
        return null;
    }

    private static String sig(MinecraftClient client) {
        StringBuilder sb = new StringBuilder();
        if (client.currentScreen != null) sb.append(client.currentScreen.getTitle().getString()).append('#');
        for (Slot s : container(client)) sb.append(s.id).append(s.getStack().getName().getString()).append('|');
        return container(client).isEmpty() ? "" : sb.toString();
    }

    private static void dump(MinecraftClient client, List<Slot> slots) {
        StringBuilder sb = new StringBuilder("=== ").append(Formatting.strip(client.currentScreen.getTitle().getString()))
                .append(" (scanning ").append(target).append(") ===\n");
        for (Slot s : slots) {
            ItemStack st = s.getStack();
            sb.append('[').append(s.id).append("] ").append(Parse.name(st)).append('\n');
            for (String l : Parse.lore(st)) sb.append("    ").append(l).append('\n');
        }
        try {
            Files.createDirectories(Store.dir());
            Files.writeString(Store.dir().resolve("menu-dump.txt"), sb + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
