package com.example.visitorwaypoints;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Visitor's Logbook while you have it open: which visitors you've had, and which
 * still have requirements you haven't met.
 */
public final class Logbook {

    private Logbook() {
    }

    private static final Pattern VISITS = Pattern.compile("(?:times visited|visits|visited)\\s*:?\\s*([0-9,]+)", Pattern.CASE_INSENSITIVE);
    private static final String YES = "✔✓☑", NO = "✖✗✘❌⨯";
    private static final Pattern GARDEN_LINE = Pattern.compile("garden\\s*(?:level\\s*)?([0-9]+|[ivx]+)\\b", Pattern.CASE_INSENSITIVE);

    /** Signature of the page we last read, so each page is read once. */
    private static String lastPage = "";
    private static final Set<String> dumpedPages = new HashSet<>();

    public static void tick(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> hs) || client.player == null) {
            lastPage = "";
            return;
        }
        String title = Formatting.strip(hs.getTitle().getString());
        if (title == null || !title.toLowerCase(Locale.ROOT).contains("logbook")) return;

        // read once the items have arrived and whenever the page changes
        StringBuilder sig = new StringBuilder();
        List<Slot> slots = new ArrayList<>();
        for (Slot s : client.player.currentScreenHandler.slots) {
            if (s.inventory == client.player.getInventory() || !s.hasStack()) continue;
            slots.add(s);
            sig.append(s.id).append(':').append(s.getStack().getName().getString()).append('|');
        }
        if (slots.isEmpty() || sig.toString().equals(lastPage)) return;
        lastPage = sig.toString();
        readPage(client, title, slots);
    }

    private static void readPage(MinecraftClient client, String title, List<Slot> slots) {
        int read = 0, locked = 0, unknownNames = 0;
        StringBuilder dump = new StringBuilder("=== ").append(title).append(" ===\n");
        for (Slot s : slots) {
            ItemStack st = s.getStack();
            String name = Formatting.strip(st.getName().getString());
            List<String> lore = lore(st);
            dump.append("[").append(s.id).append("] ").append(name).append('\n');
            for (String l : lore) dump.append("    ").append(l).append('\n');
            if (name == null) continue;
            Visitors.Visitor v = Visitors.find(name);
            if (v == null) {
                if (name.contains("?")) unknownNames++;
                continue;
            }
            Visitors.State st8 = Visitors.state(v);
            st8.seen = true;
            int visits = -1;
            boolean anyNo = false, anyYes = false, anyReqLine = false;
            Boolean levelOk = null;
            for (String l : lore) {
                Matcher m = VISITS.matcher(l);
                if (m.find() && visits < 0) {
                    try {
                        visits = Integer.parseInt(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
                boolean no = has(l, NO), yes = has(l, YES);
                if (no || yes) anyReqLine = true;
                if (no) anyNo = true;
                if (yes) anyYes = true;
                if ((no || yes) && GARDEN_LINE.matcher(l).find()) levelOk = yes && !no;
                String ll = l.toLowerCase(Locale.ROOT);
                if (ll.contains("not unlocked") || ll.contains("locked!") || ll.contains("requirement not met")) anyNo = true;
            }
            st8.visits = visits;
            st8.levelOk = levelOk;
            // unlocked = it has visited before, or the logbook ticks every requirement
            st8.unlocked = visits > 0 || (anyReqLine && anyYes && !anyNo);
            if (st8.unlocked) st8.talked = false;
            read++;
            if (!st8.unlocked) locked++;
        }
        Visitors.save();
        dumpOnce(title, dump.toString());
        if (read > 0 || unknownNames > 0) {
            String msg = "Logbook: read " + read + " visitors on this page, " + locked + " not unlocked yet";
            if (unknownNames > 0) msg += " (" + unknownNames + " hidden ??? entries I can't identify)";
            VisitorWaypointsClient.say(client, msg);
        }
    }

    private static boolean has(String s, String chars) {
        for (int i = 0; i < chars.length(); i++) if (s.indexOf(chars.charAt(i)) >= 0) return true;
        return false;
    }

    static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return out;
        for (Text t : lore.lines()) {
            String s = Formatting.strip(t.getString());
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    /** Writes each logbook page's items + lore to a file once, so the reading can be fixed if the format differs. */
    private static void dumpOnce(String title, String text) {
        String k = title + text.length();
        if (!dumpedPages.add(k)) return;
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("visitorwaypoints-logbook-dump.txt");
            Files.writeString(p, text + "\n", StandardCharsets.UTF_8,
                    dumpedPages.size() == 1 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (Exception ignored) {
        }
    }
}
