package com.example.ghostbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses a chosen item's right-click ability: switches to it, right-clicks, switches back to the
 * weapon you were holding. How often (cooldown) and when (always / low health / a group close by) are settings.
 */
public final class Ability {

    private Ability() {
    }

    public enum When { ALWAYS, LOWHP, GROUP }

    // settings (saved by GhostBotClient)
    /** Name of the item (without colors), "" = no ability item. */
    public static String item = "";
    public static int cooldownMs = 5000;
    public static When when = When.GROUP;
    /** For LOWHP: use it below this % of your health. */
    public static int hpPercent = 50;
    /** Only use it while a target is this close (ALWAYS). */
    public static int range = 8;
    /** For GROUP: use it when at least this many mobs are within range . */
    public static int group = 3;

    private enum Phase { IDLE, SWAPPED, USED }

    private static Phase phase = Phase.IDLE;
    private static int wait = 0, returnSlot = -1;
    private static long readyAt = 0;
    private static boolean warnedMissing = false;
    private static final Random RNG = new Random();
    private static final Pattern COOLDOWN = Pattern.compile("cooldown for ([0-9.]+)\\s*s", Pattern.CASE_INSENSITIVE);

    /** True while it's holding the ability item (the bot shouldn't attack with it). */
    public static boolean busy() {
        return phase != Phase.IDLE;
    }

    public static void tick(MinecraftClient client, boolean bossStage) {
        ClientPlayerEntity p = client.player;
        if (item.isEmpty() || p == null) return;

        switch (phase) {
            case IDLE -> {
                if (System.currentTimeMillis() < readyAt || !shouldUse(client, p, bossStage)) return;
                int slot = findSlot(p);
                if (slot < 0) {
                    if (!warnedMissing) {
                        GhostBotClient.say(client, "Can't find \"" + item + "\" in your hotbar, so I'm not using its ability.");
                        warnedMissing = true;
                    }
                    readyAt = System.currentTimeMillis() + 10_000;
                    return;
                }
                warnedMissing = false;
                returnSlot = p.getInventory().getSelectedSlot();
                if (slot != returnSlot) p.getInventory().setSelectedSlot(slot);
                phase = Phase.SWAPPED;
                wait = 1 + RNG.nextInt(3);                 // a moment between switching and clicking, like a hand
            }
            case SWAPPED -> {
                if (--wait > 0) return;
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                p.swingHand(Hand.MAIN_HAND);
                long jitter = Math.round(cooldownMs * (RNG.nextDouble() * 0.1));
                readyAt = System.currentTimeMillis() + cooldownMs + jitter;
                phase = Phase.USED;
                wait = 2 + RNG.nextInt(3);
            }
            case USED -> {
                if (--wait > 0) return;
                if (returnSlot >= 0 && returnSlot != p.getInventory().getSelectedSlot()) p.getInventory().setSelectedSlot(returnSlot);
                phase = Phase.IDLE;
            }
        }
    }

    private static boolean shouldUse(MinecraftClient client, ClientPlayerEntity p, boolean bossStage) {
        return switch (when) {
            case LOWHP -> p.getHealth() / p.getMaxHealth() * 100f < hpPercent;
            case ALWAYS -> targetClose(client, p);
            case GROUP -> Combat.nearby(client, range) >= group;
        };
    }

    private static boolean targetClose(MinecraftClient client, ClientPlayerEntity p) {
        return Combat.target != null && Combat.state == Combat.State.FIGHTING && p.distanceTo(Combat.target) <= range;
    }

    /** The hotbar slot holding the item (matched by name), or -1. */
    private static int findSlot(ClientPlayerEntity p) {
        String want = item.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isEmpty()) continue;
            String n = Formatting.strip(st.getName().getString());
            if (n != null && n.toLowerCase(Locale.ROOT).contains(want)) return i;
        }
        return -1;
    }

    /** Chat: the server's own cooldown / mana messages push the next use back. */
    public static void onChat(String l) {
        Matcher m = COOLDOWN.matcher(l);
        if (m.find()) {
            try {
                readyAt = Math.max(readyAt, System.currentTimeMillis() + Math.round(Double.parseDouble(m.group(1)) * 1000) + 200);
            } catch (NumberFormatException ignored) {
            }
        } else if (l.contains("not enough mana") || l.contains("don't have enough mana")) {
            readyAt = Math.max(readyAt, System.currentTimeMillis() + 3000);
        }
    }

    /** Uses the item you're holding as the ability item. Returns its name, or null if your hand is empty. */
    public static String setFromHand(ClientPlayerEntity p) {
        ItemStack st = p.getMainHandStack();
        if (st.isEmpty()) return null;
        String n = Formatting.strip(st.getName().getString());
        if (n == null || n.isBlank()) return null;
        // drop star / reforge clutter at the ends, keep the item's own name
        item = n.replaceAll("[✪➊➋➌➍➎⚚]", "").trim();
        warnedMissing = false;
        return item;
    }

    public static void reset(MinecraftClient client) {
        if (phase != Phase.IDLE && client.player != null && returnSlot >= 0) client.player.getInventory().setSelectedSlot(returnSlot);
        phase = Phase.IDLE;
        readyAt = 0;
    }

    public static String describe() {
        if (item.isEmpty()) return "none";
        long left = readyAt - System.currentTimeMillis();
        String w = switch (when) {
            case ALWAYS -> "in fights";
            case GROUP -> group + "+ ghosts within " + range + " blocks";
            case LOWHP -> "below " + hpPercent + "% HP";
        };
        return item + " (" + w + ", " + (left > 0 ? String.format("%.1fs", left / 1000.0) : "ready") + ")";
    }
}
