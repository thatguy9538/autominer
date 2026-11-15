package com.example.lanewalker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The block program the player builds in the editor: repeat N times { steps }. */
public class Program {

    public enum Dir {
        FORWARD, BACK, LEFT, RIGHT;

        public Dir next() { return values()[(ordinal() + 1) % values().length]; }
        public Dir prev() { return values()[(ordinal() + values().length - 1) % values().length]; }
        public String label() { return name().toLowerCase(); }
    }

    public enum Kind { WALK_WALL, WALK_BLOCKS, WAIT }

    public static class Step {
        public Kind kind;
        public Dir dir;
        public int amount;

        public Step(Kind kind, Dir dir, int amount) {
            this.kind = kind;
            this.dir = dir;
            this.amount = amount;
        }

        public Step copy() { return new Step(kind, dir, amount); }

        public String describe() {
            return switch (kind) {
                case WALK_WALL -> "walk " + dir.label() + " until wall";
                case WALK_BLOCKS -> "walk " + dir.label() + " " + amount + (amount == 1 ? " block" : " blocks");
                case WAIT -> "wait " + amount + (amount == 1 ? " second" : " seconds");
            };
        }
    }

    public static final int MAX_STEPS = 30;
    public static final int MAX_AMOUNT = 999;

    /** Round trips through the steps before stopping; 0 = forever. */
    public int repeats = 0;
    public final List<Step> steps = new ArrayList<>();

    public static Program defaults() {
        Program p = new Program();
        p.steps.add(new Step(Kind.WALK_WALL, Dir.LEFT, 5));
        p.steps.add(new Step(Kind.WALK_WALL, Dir.RIGHT, 5));
        return p;
    }

    // ---- saving / loading (simple text file in .minecraft/config) ----

    public void save(Path file) {
        StringBuilder sb = new StringBuilder("repeat ").append(repeats).append('\n');
        for (Step s : steps) {
            sb.append(s.kind.name()).append(' ').append(s.dir.name()).append(' ').append(s.amount).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static Program load(Path file) {
        if (!Files.exists(file)) return defaults();
        try {
            Program p = new Program();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals("repeat")) {
                    p.repeats = clamp(Integer.parseInt(parts[1]), 0, 100000);
                } else if (parts.length == 3 && p.steps.size() < MAX_STEPS) {
                    p.steps.add(new Step(Kind.valueOf(parts[0]), Dir.valueOf(parts[1]),
                            clamp(Integer.parseInt(parts[2]), 1, MAX_AMOUNT)));
                }
            }
            return p;
        } catch (Exception e) {
            return defaults();
        }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
