package com.example.slayerbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts a slayer quest: opens the slayer menu (/slayer by default) and clicks through it:
 * collect a finished quest's reward, the slayer, the tier, then Confirm.
 */
public final class Starter {

    private Starter() {
    }

    public static boolean busy = false;
    private static int timer = 0, settle = 0, clicks = 0;
    private static String lastSig = "";
    private static boolean opened = false;
    /** After a failed attempt, don't try the command again until this tick count. */
    private static long retryAt = 0, ticks = 0;
    public static String note = "";

    public static void tick(MinecraftClient client) {
        ticks++;
        if (!busy) return;
        if (--timer <= 0) {
            fail(client, opened ? "The slayer menu stopped responding."
                    : "/" + SlayerBotClient.startCommand + " didn't open a menu. Start the quest yourself (Maddox in the Hub"
                    + " tavern, or your Batphone); I'll keep fighting once it's running.");
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            if (opened) finish(client);                       // the menu closed by itself: quest started
            return;
        }
        opened = true;
        String sig = sig(client);
        if (sig.equals(lastSig) || sig.isEmpty()) return;
        if (++settle < 5) return;
        List<Slot> slots = container(client);
        dump(client, slots);
        Slot pick = choose(slots);
        if (pick == null || clicks >= 6) {
            if (Quest.stage == Quest.Stage.XP || Quest.stage == Quest.Stage.BOSS) {
                finish(client);
                return;
            }
            fail(client, "Couldn't find the " + SlayerBotClient.type.boss + " " + Quest.roman(SlayerBotClient.tier)
                    + " button (menus saved to config/slayerbot-dump.txt).");
            return;
        }
        lastSig = sig;
        settle = 0;
        clicks++;
        timer = 60;
        client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pick.id, 0, SlotActionType.PICKUP, client.player);
    }

    public static void start(MinecraftClient client) {
        if (busy || ticks < retryAt || client.player == null) return;
        busy = true;
        opened = false;
        clicks = 0;
        settle = 0;
        timer = 80;
        lastSig = "";
        note = "Starting a quest";
        client.player.networkHandler.sendChatCommand(SlayerBotClient.startCommand);
    }

    /** What to click: collect > confirm > the tier > the slayer. */
    private static Slot choose(List<Slot> slots) {
        String boss = SlayerBotClient.type.boss.toLowerCase(Locale.ROOT);
        String tierName = boss + " " + Quest.roman(SlayerBotClient.tier).toLowerCase(Locale.ROOT);
        Slot collect = null, confirm = null, tier = null, slayer = null;
        for (Slot s : slots) {
            String n = name(s.getStack()), lore = lore(s.getStack());
            if (collect == null && (lore.contains("click to collect") || n.contains("collect") || n.contains("claim"))) collect = s;
            if (confirm == null && (n.equals("confirm") || n.startsWith("confirm"))) confirm = s;
            if (tier == null && (n.equals(tierName) || (n.contains(boss) && n.endsWith(" " + Quest.roman(SlayerBotClient.tier).toLowerCase(Locale.ROOT))))) tier = s;
            if (tier == null && n.equals("tier " + Quest.roman(SlayerBotClient.tier).toLowerCase(Locale.ROOT))) tier = s;
            if (slayer == null && n.contains(boss)) slayer = s;
        }
        if (collect != null) return collect;
        if (confirm != null) return confirm;
        if (tier != null) return tier;
        return slayer;
    }

    private static void finish(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
        busy = false;
        note = "";
        retryAt = ticks + 100;
    }

    private static void fail(MinecraftClient client, String why) {
        if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
        busy = false;
        retryAt = ticks + 20 * 60;                           // try again in a minute
        note = "Waiting for you to start the quest";
        SlayerBotClient.say(client, why);
    }

    public static void reset() {
        busy = false;
        note = "";
        retryAt = 0;
    }

    private static List<Slot> container(MinecraftClient client) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : client.player.currentScreenHandler.slots) {
            if (s.inventory != client.player.getInventory() && s.hasStack()) out.add(s);
        }
        return out;
    }

    private static String sig(MinecraftClient client) {
        StringBuilder sb = new StringBuilder(client.currentScreen.getTitle().getString()).append('#');
        for (Slot s : container(client)) sb.append(s.id).append(s.getStack().getName().getString()).append('|');
        return container(client).isEmpty() ? "" : sb.toString();
    }

    static String name(ItemStack st) {
        String n = Formatting.strip(st.getName().getString());
        return n == null ? "" : n.toLowerCase(Locale.ROOT).trim();
    }

    static String lore(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text t : lore.lines()) sb.append(Formatting.strip(t.getString())).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static void dump(MinecraftClient client, List<Slot> slots) {
        StringBuilder sb = new StringBuilder("=== ").append(Formatting.strip(client.currentScreen.getTitle().getString())).append(" ===\n");
        for (Slot s : slots) sb.append('[').append(s.id).append("] ").append(name(s.getStack())).append("  |  ").append(lore(s.getStack())).append('\n');
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("slayerbot-dump.txt"), sb + "\n", StandardCharsets.UTF_8,
                    clicks == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (Exception ignored) {
        }
    }
}
