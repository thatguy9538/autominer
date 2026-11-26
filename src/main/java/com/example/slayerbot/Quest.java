package com.example.slayerbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The slayer types, and the quest as the SkyBlock sidebar shows it. */
public final class Quest {

    private Quest() {
    }

    /** One slayer: its boss name (as on nametags and in the Maddox menu) and which mobs give XP. */
    public enum Type {
        REVENANT("Revenant Horror", e -> e instanceof ZombieEntity),
        TARANTULA("Tarantula Broodfather", e -> e instanceof SpiderEntity),
        SVEN("Sven Packmaster", e -> e instanceof WolfEntity w && !w.isTamed()),
        VOIDGLOOM("Voidgloom Seraph", e -> e instanceof EndermanEntity),
        INFERNO("Inferno Demonlord", e -> e instanceof BlazeEntity);

        public final String boss;
        public final Predicate<Entity> mob;

        Type(String boss, Predicate<Entity> mob) {
            this.boss = boss;
            this.mob = mob;
        }

        public static Type parse(String s) {
            String k = s.toLowerCase(Locale.ROOT);
            for (Type t : values()) {
                if (t.name().toLowerCase(Locale.ROOT).startsWith(k) || t.boss.toLowerCase(Locale.ROOT).contains(k)) return t;
            }
            return null;
        }
    }

    public enum Stage { NONE, XP, BOSS, DONE }

    public static Stage stage = Stage.NONE;
    /** Combat XP / kills so far and needed, while in the XP stage (-1 = unknown). */
    public static long have = -1, need = -1;
    /** The quest name on the sidebar ("Revenant Horror IV"). */
    public static String name = "";

    private static final Pattern PROGRESS = Pattern.compile("\\(?([0-9][0-9,.]*[kKmM]?)\\s*/\\s*([0-9][0-9,.]*[kKmM]?)\\)?");

    /** Re-reads the sidebar. */
    public static void read(MinecraftClient client) {
        List<String> lines = sidebar(client.world);
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains("slayer quest")) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            stage = Stage.NONE;
            name = "";
            have = need = -1;
            return;
        }
        name = at + 1 < lines.size() ? lines.get(at + 1).trim() : "";
        Stage s = Stage.XP;
        have = need = -1;
        for (int i = at + 1; i < Math.min(lines.size(), at + 5); i++) {
            String l = lines.get(i).toLowerCase(Locale.ROOT);
            if (l.contains("slay the boss")) s = Stage.BOSS;
            if (l.contains("boss slain")) s = Stage.DONE;
            Matcher m = PROGRESS.matcher(lines.get(i));
            if (m.find() && (l.contains("xp") || l.contains("kill") || l.contains("combat"))) {
                have = num(m.group(1));
                need = num(m.group(2));
            }
        }
        stage = s;
    }

    /** The sidebar's lines, top to bottom, without colors. */
    static List<String> sidebar(ClientWorld world) {
        List<String> out = new ArrayList<>();
        if (world == null) return out;
        try {
            Scoreboard sb = world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return out;
            List<ScoreboardEntry> entries = new ArrayList<>(sb.getScoreboardEntries(obj));
            entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed());
            for (ScoreboardEntry e : entries) {
                Team team = sb.getScoreHolderTeam(e.owner());
                String line = Formatting.strip(Team.decorateName(team, e.name()).getString());
                if (line != null) out.add(line.replaceAll("[^\\x20-\\x7E\\u00A0-\\u024F]", "").trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static long num(String s) {
        s = s.replace(",", "");
        double mult = 1;
        char c = Character.toLowerCase(s.charAt(s.length() - 1));
        if (c == 'k') mult = 1e3;
        if (c == 'm') mult = 1e6;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            return Math.round(Double.parseDouble(s) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "IV" for 4. */
    public static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }
}
