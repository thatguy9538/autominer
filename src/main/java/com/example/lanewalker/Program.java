package com.example.lanewalker;

import java.util.ArrayList;
import java.util.List;

/** One script: "when [key] pressed" + repeat N times { steps }. */
public class Program {

    public enum Dir {
        FORWARD, BACK, LEFT, RIGHT;

        public Dir next() { return values()[(ordinal() + 1) % values().length]; }
        public Dir prev() { return values()[(ordinal() + values().length - 1) % values().length]; }
        public String label() { return name().toLowerCase(); }
    }

    public enum Kind {
        WALK_WALL, WALK_BLOCKS, WAIT, WAIT_RANDOM, SET_ANGLE, STOP_AT, GO_TO, IF, IF_AT;

        public boolean isWalk() { return this == WALK_WALL || this == WALK_BLOCKS; }

        /** Orange "Control" blocks (everything else is blue "Motion"). */
        public boolean isControl() { return isWait() || this == STOP_AT || this == IF || this == IF_AT; }

        public boolean isWait() { return this == WAIT || this == WAIT_RANDOM; }
    }

    public static class Step {
        public Kind kind;
        public Dir dir;
        public int amount;
        /** WAIT_RANDOM: amount = min, amount2 = max, in tenths of a second.
         *  SET_ANGLE:   amount = yaw (x), amount2 = pitch (y), in degrees.
         *  STOP_AT:     amount = block X, amount2 = block Z.
         *  IF_AT:       amount = block X, amount2 = block Z.
         *  GO_TO:       amount = X, amount2 = Y, amount3 = Z. */
        public int amount2;
        public int amount3;
        /** Unique id, so "if" links survive reordering. */
        public int id;
        /** IF only: id of the linked block, or -1. */
        public int link = -1;

        public Step(Kind kind, Dir dir, int amount) {
            this(kind, dir, amount, amount);
        }

        public Step(Kind kind, Dir dir, int amount, int amount2) {
            this(kind, dir, amount, amount2, 0);
        }

        public Step(Kind kind, Dir dir, int amount, int amount2, int amount3) {
            this.kind = kind;
            this.dir = dir;
            this.amount = amount;
            this.amount2 = amount2;
            this.amount3 = amount3;
            this.id = nextId++;
        }

        /** A fresh copy with its own id (used when dragging out of the palette). */
        public Step copy() { return new Step(kind, dir, amount, amount2, amount3); }

        public String describe() {
            return switch (kind) {
                case WALK_WALL -> "walk " + dir.label() + " until wall";
                case WALK_BLOCKS -> "walk " + dir.label() + " " + amount + (amount == 1 ? " block" : " blocks");
                case WAIT -> "wait " + amount + (amount == 1 ? " second" : " seconds");
                case WAIT_RANDOM -> "random wait " + tenths(amount) + " to " + tenths(amount2) + " s";
                case SET_ANGLE -> "set angle x " + amount + " y " + amount2;
                case STOP_AT -> "stop at x " + amount + " z " + amount2;
                case GO_TO -> "go to " + amount + " " + amount2 + " " + amount3;
                case IF -> "if linked block is done, run next";
                case IF_AT -> "if at x " + amount + " z " + amount2 + ", run next";
            };
        }
    }

    public static final int MAX_STEPS = 30;
    public static final int MAX_AMOUNT = 999;
    public static final int COORD_LIMIT = 30_000_000;
    private static int nextId = 1;

    public Step byId(int id) {
        for (Step s : steps) if (s.id == id) return s;
        return null;
    }
    public static final int MIN_Y = -2048, MAX_Y = 4096;
    /** Longest random wait, in tenths of a second (60 s). */
    public static final int MAX_TENTHS = 600;

    public static String tenths(int t) {
        return (t / 10) + "." + (t % 10);
    }

    /** GLFW key code that runs this script, or -1 for none. */
    public int key = org.lwjgl.glfw.GLFW.GLFW_KEY_K;
    /** Round trips through the steps before stopping; 0 = forever. */
    public int repeats = 0;
    public final List<Step> steps = new ArrayList<>();

    public static Program defaults() {
        Program p = new Program();
        p.steps.add(new Step(Kind.WALK_WALL, Dir.LEFT, 5));
        p.steps.add(new Step(Kind.WALK_WALL, Dir.RIGHT, 5));
        return p;
    }

    // ---- saving / loading (see Workspace for the file itself) ----

    void writeTo(StringBuilder sb) {
        sb.append("script ").append(key).append('\n');
        sb.append("repeat ").append(repeats).append('\n');
        for (Step s : steps) {
            sb.append("step ").append(s.kind.name()).append(' ').append(s.dir.name()).append(' ').append(s.amount)
                    .append(' ').append(s.amount2).append(' ').append(s.amount3)
                    .append(' ').append(s.id).append(' ').append(s.link).append('\n');
        }
    }

    /** Reads one "repeat ..." or "step ..." line (also the older formats) into this script. */
    void readLine(String[] parts) {
        int id = -1, link = -1;
        if (parts.length == 8 && parts[0].equals("step")) {   // newest format
            id = Integer.parseInt(parts[6]);
            link = Integer.parseInt(parts[7]);
            parts = java.util.Arrays.copyOfRange(parts, 1, 6);
        }
        if (parts.length == 2 && parts[0].equals("repeat")) {
            repeats = clamp(Integer.parseInt(parts[1]), 0, 100000);
        } else if (parts.length >= 3 && parts.length <= 5 && steps.size() < MAX_STEPS) {
            Kind kind = Kind.valueOf(parts[0]);
            int a = Integer.parseInt(parts[2]);
            int b = parts.length >= 4 ? Integer.parseInt(parts[3]) : a;
            int c = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;
            if (kind == Kind.GO_TO) {
                a = clamp(a, -COORD_LIMIT, COORD_LIMIT);
                b = clamp(b, MIN_Y, MAX_Y);
                c = clamp(c, -COORD_LIMIT, COORD_LIMIT);
            } else if (kind == Kind.STOP_AT || kind == Kind.IF_AT) {
                a = clamp(a, -COORD_LIMIT, COORD_LIMIT);
                b = clamp(b, -COORD_LIMIT, COORD_LIMIT);
            } else if (kind == Kind.SET_ANGLE) {
                a = clamp(a, -180, 180);
                b = clamp(b, -90, 90);
            } else if (kind == Kind.WAIT_RANDOM) {
                a = clamp(a, 0, MAX_TENTHS);
                b = clamp(b, 0, MAX_TENTHS);
            } else {
                a = clamp(a, 1, MAX_AMOUNT);
            }
            Step st = new Step(kind, Dir.valueOf(parts[1]), a, b, c);
            if (id > 0) {
                st.id = id;
                nextId = Math.max(nextId, id + 1);
            }
            st.link = link;
            steps.add(st);
        }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
