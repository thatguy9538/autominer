package com.example.powderassist;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Powder tracker: reads your powder totals from the tab list (or adds up the chest
 * reward lines in chat if the tab doesn't show them) and works out powder per hour.
 */
public final class Tracker {

    private Tracker() {
    }

    public static final String[] TYPES = {"Mithril", "Gemstone", "Glacite"};
    /** Powder total at the start of the session, from the tab list (-1 = not seen yet). */
    private static final long[] start = {-1, -1, -1};
    private static final long[] latest = {-1, -1, -1};
    /** Powder added up from chat, for when the tab list doesn't show totals. */
    private static final long[] fromChat = {0, 0, 0};
    public static int chestsFound = 0, chestsOpened = 0;
    private static long sessionStart = System.currentTimeMillis();
    /** Time spent paused (no powder gained for a while) doesn't count toward the rate. */
    private static long activeMs = 0, lastGainMs = 0, lastTickMs = 0;
    private static final long IDLE_MS = 60_000;
    private static int ticks = 0;

    private static final Pattern TAB = Pattern.compile("^\\s*(Mithril|Gemstone|Glacite)(?: Powder)?:\\s*([0-9][0-9,]*)\\s*$");
    private static final Pattern CHAT_A = Pattern.compile("\\+?\\s*([0-9][0-9,]*)\\s*(?:x\\s*)?(Mithril|Gemstone|Glacite) Powder");
    private static final Pattern CHAT_B = Pattern.compile("(Mithril|Gemstone|Glacite) Powder\\s*x\\s*([0-9][0-9,]*)");

    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (lastTickMs != 0 && now - lastGainMs < IDLE_MS) activeMs += now - lastTickMs;
        lastTickMs = now;
        if (++ticks % 20 != 0 || client.getNetworkHandler() == null) return;     // once a second
        for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
            Text t = e.getDisplayName();
            if (t == null) continue;
            String s = Formatting.strip(t.getString());
            if (s == null) continue;
            Matcher m = TAB.matcher(s);
            if (!m.matches()) continue;
            int i = index(m.group(1));
            long v = parse(m.group(2));
            if (v < 0) continue;
            if (start[i] < 0) start[i] = v;
            if (latest[i] >= 0 && v > latest[i]) lastGainMs = now;
            if (v < start[i]) start[i] = v;               // spent some powder: don't go negative
            latest[i] = v;
        }
    }

    public static void onChat(String s) {
        Matcher m = CHAT_A.matcher(s);
        if (m.find()) {
            add(index(m.group(2)), parse(m.group(1)));
            return;
        }
        m = CHAT_B.matcher(s);
        if (m.find()) add(index(m.group(1)), parse(m.group(2)));
    }

    private static void add(int i, long v) {
        if (i < 0 || v <= 0) return;
        fromChat[i] += v;
        lastGainMs = System.currentTimeMillis();
    }

    /** Powder gained this session for a type (tab list if we have it, chat otherwise). */
    public static long gained(int i) {
        if (start[i] >= 0 && latest[i] >= 0) return latest[i] - start[i];
        return fromChat[i];
    }

    /** Per hour, over the time you were actually getting powder. */
    public static long perHour(long amount) {
        double hours = Math.max(activeMs, 60_000) / 3_600_000.0;
        return Math.round(amount / hours);
    }

    public static long sessionMs() {
        return System.currentTimeMillis() - sessionStart;
    }

    public static long activeMs() {
        return activeMs;
    }

    public static boolean any() {
        for (int i = 0; i < 3; i++) if (gained(i) > 0) return true;
        return false;
    }

    public static void reset() {
        for (int i = 0; i < 3; i++) {
            start[i] = latest[i];
            fromChat[i] = 0;
        }
        chestsFound = 0;
        chestsOpened = 0;
        sessionStart = System.currentTimeMillis();
        activeMs = 0;
        lastGainMs = 0;
    }

    private static int index(String type) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equalsIgnoreCase(type)) return i;
        return -1;
    }

    private static long parse(String n) {
        try {
            return Long.parseLong(n.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String shortNum(long n) {
        if (Math.abs(n) >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (Math.abs(n) >= 10_000) return String.format("%.1fk", n / 1000.0);
        return String.format("%,d", n);
    }
}
