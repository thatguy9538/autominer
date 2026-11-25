package com.example.rankscout;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads skills and collections out of menu items (name + lore), whatever menu they're in. */
public final class Parse {

    private Parse() {
    }

    public static final Set<String> SKILLS = Set.of("Farming", "Mining", "Combat", "Foraging", "Fishing", "Enchanting",
            "Alchemy", "Taming", "Carpentry", "Runecrafting", "Social", "Dungeoneering", "Catacombs", "Hunting");

    /** One stat value: a level / tier and, when shown, an amount (skill XP or items collected). */
    public static final class Stat {
        public double level = -1;
        public double amount = -1;

        /** The number used to rank: amount if known, else level. */
        public double score() {
            return amount >= 0 ? amount : level;
        }

        public boolean better(Stat o) {
            if (o == null) return true;
            if (amount >= 0 && o.amount >= 0) return amount > o.amount;
            return level > o.level;
        }
    }

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?)";
    private static final Pattern NAME_LEVEL = Pattern.compile("^(?:[^A-Za-z]*)([A-Za-z][A-Za-z' ]*?)(?:\\s+(?:Skill|Collection))?\\s+([IVXLC]+|[0-9]+)$");
    private static final Pattern LINE_LEVEL = Pattern.compile("^\\W*([A-Za-z][A-Za-z' ]*?)(?:\\s+Skill)?\\s*(?::|-)?\\s+(?:Level\\s+)?([IVXLC]+|[0-9]+)(?:\\s|$|[:(])");
    private static final Pattern TOTAL_XP = Pattern.compile("(?:total\\s+)?(?:skill\\s+)?(?:experience|xp)\\s*:?\\s*" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern XP_AFTER = Pattern.compile(NUM + "\\s*(?:total\\s+)?(?:xp|exp|experience)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLLECTED = Pattern.compile("(?:total\\s+)?collected\\s*:?\\s*" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_COLON_NUM = Pattern.compile("^\\W*([A-Za-z][A-Za-z' ]*?)\\s*:\\s*" + NUM + "\\s*$");

    public static String name(ItemStack st) {
        String n = Formatting.strip(st.getName().getString());
        return n == null ? "" : n.trim();
    }

    public static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return out;
        for (Text t : lore.lines()) {
            String s = Formatting.strip(t.getString());
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** Adds every skill / collection this item shows into the maps. */
    public static void item(ItemStack st, Map<String, Stat> skills, Map<String, Stat> collections) {
        String name = name(st);
        List<String> lore = lore(st);

        // item named like "Farming XXV" / "Wheat VII" / "Mining 30"
        Matcher m = NAME_LEVEL.matcher(name);
        if (m.matches()) {
            String base = clean(m.group(1));
            double lvl = roman(m.group(2));
            String loreAll = String.join("\n", lore);
            if (isSkill(base)) {
                Stat s = skills.computeIfAbsent(canonSkill(base), k -> new Stat());
                s.level = Math.max(s.level, lvl);
                double xp = firstNum(loreAll, TOTAL_XP, XP_AFTER);
                if (xp >= 0) s.amount = Math.max(s.amount, xp);
            } else {
                double got = firstNum(loreAll, COLLECTED);
                boolean looksCollection = got >= 0 || loreAll.toLowerCase(Locale.ROOT).contains("collection");
                if (looksCollection && base.length() >= 3 && !isMenuWord(base)) {
                    Stat s = collections.computeIfAbsent(base, k -> new Stat());
                    s.level = Math.max(s.level, lvl);
                    if (got >= 0) s.amount = Math.max(s.amount, got);
                }
            }
        }

        // lore lines like "Farming 25", "Mining: XXX", "Combat Skill: 18" or "Wheat: 12,345"
        boolean collectionContext = name.toLowerCase(Locale.ROOT).contains("collection");
        for (String l : lore) {
            Matcher lm = LINE_LEVEL.matcher(l);
            if (lm.find()) {
                String base = clean(lm.group(1));
                if (isSkill(base)) {
                    Stat s = skills.computeIfAbsent(canonSkill(base), k -> new Stat());
                    s.level = Math.max(s.level, roman(lm.group(2)));
                    continue;
                }
            }
            Matcher cm = NAME_COLON_NUM.matcher(l);
            if (cm.find()) {
                String base = clean(cm.group(1));
                double v = num(cm.group(2));
                if (isSkill(base)) {
                    Stat s = skills.computeIfAbsent(canonSkill(base), k -> new Stat());
                    if (v <= 80) s.level = Math.max(s.level, v);
                    else s.amount = Math.max(s.amount, v);
                } else if (collectionContext && base.length() >= 3 && !isMenuWord(base)) {
                    Stat s = collections.computeIfAbsent(base, k -> new Stat());
                    s.amount = Math.max(s.amount, v);
                }
            }
        }
    }

    /** Is this an item worth clicking to reach more stats (a Skills / Collections button or category)? */
    public static boolean isStatsButton(ItemStack st) {
        String n = name(st).toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return false;
        if (n.contains("close") || n.contains("go back") || n.contains("previous") || n.contains("next page")) return false;
        if (n.matches(".*\\bskills?\\b.*") && !n.matches(".*\\b[ivxlc]+$")) return true;
        if (n.matches(".*\\bcollections?\\b.*")) {
            String lore = String.join(" ", lore(st)).toLowerCase(Locale.ROOT);
            return lore.contains("click") || lore.contains("view") || n.equals("collection") || n.equals("collections")
                    || n.endsWith(" collections") || n.endsWith(" collection");
        }
        return false;
    }

    static boolean isSkill(String base) {
        return SKILLS.stream().anyMatch(s -> s.equalsIgnoreCase(base));
    }

    static String canonSkill(String base) {
        for (String s : SKILLS) if (s.equalsIgnoreCase(base)) return s;
        return base;
    }

    private static boolean isMenuWord(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("page") || l.contains("level") || l.contains("skyblock") || l.contains("profile")
                || l.contains("slot") || l.contains("tier") || l.contains("rank") || l.contains("collection");
    }

    private static String clean(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static double firstNum(String text, Pattern... ps) {
        for (Pattern p : ps) {
            Matcher m = p.matcher(text);
            if (m.find()) return num(m.group(1));
        }
        return -1;
    }

    /** "12,345" / "1.5M" / "20k" -> number. */
    public static double num(String s) {
        s = s.replace(",", "").trim();
        double mult = 1;
        char c = Character.toLowerCase(s.charAt(s.length() - 1));
        if (c == 'k') mult = 1e3;
        if (c == 'm') mult = 1e6;
        if (c == 'b') mult = 1e9;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "XXV" or "25" -> 25. */
    public static double roman(String s) {
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
