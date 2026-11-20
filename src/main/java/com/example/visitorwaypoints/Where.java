package com.example.visitorwaypoints;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Works out which island you're on (tab list "Area:", else the sidebar zone) and your Garden level. */
public final class Where {

    private Where() {
    }

    /** Current island ("Hub", "Dwarven Mines"...) or null if unknown. */
    public static String island = null;
    /** Where the island came from, for /vw status. */
    public static String islandSource = "";

    private static final Pattern AREA = Pattern.compile("^\\s*(?:Area|Dungeon|Island)\\s*:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GARDEN_LEVEL = Pattern.compile("Garden\\s+Level\\s*:?\\s*([0-9]+|[IVXLC]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GARDEN_LEVEL_UP = Pattern.compile("GARDEN\\s+LEVEL\\s+UP.*?([IVXLC]+|[0-9]+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static int ticks = 0;

    public static void tick(MinecraftClient client) {
        if (++ticks % 20 != 0 || client.getNetworkHandler() == null || client.world == null) return;
        String found = null, source = "";
        for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
            Text t = e.getDisplayName();
            if (t == null) continue;
            String s = Formatting.strip(t.getString());
            if (s == null) continue;
            Matcher m = AREA.matcher(s);
            if (m.matches() && found == null) {
                found = normalizeIsland(m.group(1));
                source = "tab list";
            }
            Matcher g = GARDEN_LEVEL.matcher(s);
            if (g.find()) VisitorWaypointsClient.setGardenLevel(parseLevel(g.group(1)));
        }
        if (found == null) {
            String zone = sidebarZone(client.world);
            if (zone != null) {
                String isl = Visitors.ZONE_ISLAND.get(Visitors.key(zone));
                if (isl != null) {
                    found = isl;
                    source = "sidebar zone " + zone;
                } else if (zone.toLowerCase(Locale.ROOT).contains("garden")) {
                    found = "Garden";
                    source = "sidebar";
                }
            }
        }
        island = found;
        islandSource = source;
    }

    /** Garden level from chat ("GARDEN LEVEL UP ... XIII") or any menu item named "Garden Level XII". */
    public static void onText(String s) {
        Matcher m = GARDEN_LEVEL_UP.matcher(s);
        if (m.find()) {
            VisitorWaypointsClient.setGardenLevel(parseLevel(m.group(1)));
            return;
        }
        m = GARDEN_LEVEL.matcher(s);
        if (m.find()) VisitorWaypointsClient.setGardenLevel(parseLevel(m.group(1)));
    }

    /** The "⏣ Zone" line from the SkyBlock sidebar, or null. */
    private static String sidebarZone(ClientWorld world) {
        try {
            Scoreboard sb = world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return null;
            for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
                Team team = sb.getScoreHolderTeam(e.owner());
                String line = Formatting.strip(Team.decorateName(team, e.name()).getString());
                if (line == null) continue;
                int i = line.indexOf('⏣');
                if (i < 0) i = line.indexOf('ф');                 // the Rift uses a different symbol
                if (i >= 0) return line.substring(i + 1).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Tab "Area:" names -> the island names used in the data. */
    static String normalizeIsland(String raw) {
        String k = Visitors.key(raw);
        return switch (k) {
            case "hub", "thehub" -> "Hub";
            case "dwarvenmines" -> "Dwarven Mines";
            case "crystalhollows" -> "Crystal Hollows";
            case "spidersden" -> "Spider's Den";
            case "crimsonisle" -> "Crimson Isle";
            case "deepcaverns" -> "Deep Caverns";
            case "goldmine" -> "Gold Mine";
            case "dungeonhub" -> "Dungeon Hub";
            case "jerrysworkshop", "jerryworkshop" -> "Jerry's Workshop";
            case "thepark", "park" -> "The Park";
            case "thefarmingislands", "farmingislands", "thebarn", "mushroomdesert" -> "The Farming Islands";
            case "theend", "end" -> "The End";
            case "privateisland", "yourisland" -> "Private Island";
            case "garden", "thegarden" -> "Garden";
            default -> raw.trim();
        };
    }

    static int parseLevel(String s) {
        s = s.trim().toUpperCase(Locale.ROOT);
        if (s.matches("[0-9]+")) return Integer.parseInt(s);
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }
}
