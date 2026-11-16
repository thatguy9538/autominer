package com.example.lanewalker;

import java.util.ArrayList;
import java.util.List;

/**
 * One script: "when [key] pressed" followed by a list of blocks. Repeat and if
 * blocks are "C" blocks that hold their own list of blocks inside them.
 */
public class Program {

    public enum Dir {
        FORWARD, BACK, LEFT, RIGHT;

        public Dir next() { return values()[(ordinal() + 1) % values().length]; }
        public Dir prev() { return values()[(ordinal() + values().length - 1) % values().length]; }
        public String label() { return name().toLowerCase(); }
    }

    public enum Kind {
        WALK_WALL, WALK_BLOCKS, WAIT, WAIT_RANDOM, SET_ANGLE, STOP_AT, GO_TO, IF, IF_AT, REPEAT;

        public boolean isWalk() { return this == WALK_WALL || this == WALK_BLOCKS; }

        /** Orange "Control" blocks (everything else is blue "Motion"). */
        public boolean isControl() {
            return isWait() || this == STOP_AT || this == IF || this == IF_AT || this == REPEAT;
        }

        public boolean isWait() { return this == WAIT || this == WAIT_RANDOM; }

        /** "C" blocks that hold other blocks inside them. */
        public boolean isContainer() { return this == IF || this == IF_AT || this == REPEAT; }
    }

    public static class Step {
        public Kind kind;
        public Dir dir;
        /** WAIT / WALK_BLOCKS: the number.
         *  WAIT_RANDOM: amount = min, amount2 = max, in tenths of a second.
         *  SET_ANGLE:   amount = yaw (x), amount2 = pitch (y), in degrees.
         *  STOP_AT, IF_AT: amount = block X, amount2 = block Z.
         *  GO_TO:       amount = X, amount2 = Y, amount3 = Z.
         *  REPEAT:      amount = times, 0 = forever. */
        public int amount;
        public int amount2;
        public int amount3;
        /** Unique id, so "if" strings survive reordering. */
        public int id;
        /** IF only: id of the linked block, or -1. */
        public int link = -1;
        /** Blocks inside a REPEAT / IF / IF_AT. */
        public final List<Step> children = new ArrayList<>();

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
                case IF -> "if linked block is done";
                case IF_AT -> "if at x " + amount + " z " + amount2;
                case REPEAT -> amount == 0 ? "repeat forever" : "repeat " + amount + " times";
            };
        }
    }

    public static final int MAX_STEPS = 30;
    public static final int MAX_AMOUNT = 999;
    public static final int COORD_LIMIT = 30_000_000;
    public static final int MIN_Y = -2048, MAX_Y = 4096;
    /** Longest random wait, in tenths of a second (60 s). */
    public static final int MAX_TENTHS = 600;
    private static int nextId = 1;

    public static String tenths(int t) {
        return (t / 10) + "." + (t % 10);
    }

    /** GLFW key code that runs this script, or -1 for none. */
    public int key = org.lwjgl.glfw.GLFW.GLFW_KEY_K;
    public final List<Step> steps = new ArrayList<>();

    /** Every block in the script, including ones inside repeat / if blocks. */
    public List<Step> allSteps() {
        List<Step> out = new ArrayList<>();
        collect(steps, out);
        return out;
    }

    private static void collect(List<Step> list, List<Step> out) {
        for (Step s : list) {
            out.add(s);
            collect(s.children, out);
        }
    }

    public Step byId(int id) {
        for (Step s : allSteps()) if (s.id == id) return s;
        return null;
    }

    /** The list a block sits in (the script itself or a C block's inside), or null. */
    public List<Step> parentListOf(Step step) {
        return findParent(steps, step);
    }

    private static List<Step> findParent(List<Step> list, Step step) {
        for (Step s : list) {
            if (s == step) return list;
            List<Step> inside = findParent(s.children, step);
            if (inside != null) return inside;
        }
        return null;
    }

    public static Program defaults() {
        Program p = new Program();
        Step loop = new Step(Kind.REPEAT, Dir.LEFT, 0);
        loop.children.add(new Step(Kind.WALK_WALL, Dir.LEFT, 5));
        loop.children.add(new Step(Kind.WALK_WALL, Dir.RIGHT, 5));
        p.steps.add(loop);
        return p;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Give any block whose id is already taken (in any script) a new one. */
    static void makeIdsUnique(List<Program> scripts) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Program p : scripts) for (Step s : p.allSteps()) nextId = Math.max(nextId, s.id + 1);
        for (Program p : scripts) {
            for (Step s : p.allSteps()) {
                if (!seen.add(s.id)) {
                    s.id = nextId++;
                    seen.add(s.id);
                }
            }
        }
    }

    // ---- saving / loading (see Workspace for the file itself) ----
    //
    //   script <key>
    //   step <KIND> <DIR> <a> <b> <c> <id> <link>
    //   {                 <- the blocks inside the C block above
    //   step ...
    //   }

    void writeTo(StringBuilder sb) {
        sb.append("script ").append(key).append('\n');
        writeList(sb, steps);
    }

    private static void writeList(StringBuilder sb, List<Step> list) {
        for (Step s : list) {
            sb.append("step ").append(s.kind.name()).append(' ').append(s.dir.name()).append(' ').append(s.amount)
                    .append(' ').append(s.amount2).append(' ').append(s.amount3)
                    .append(' ').append(s.id).append(' ').append(s.link).append('\n');
            if (s.kind.isContainer()) {
                sb.append("{\n");
                writeList(sb, s.children);
                sb.append("}\n");
            }
        }
    }

    // loading state
    private final List<List<Step>> loadStack = new ArrayList<>();
    private Step lastLoaded = null;
    private int legacyRepeat = -1;      // older saves: the whole script sat in one repeat
    private boolean sawBraces = false;

    /** Reads one line of a saved script (also older formats). */
    void readLine(String[] parts) {
        if (loadStack.isEmpty()) loadStack.add(steps);
        List<Step> target = loadStack.get(loadStack.size() - 1);

        if (parts.length == 1 && parts[0].equals("{")) {
            sawBraces = true;
            if (lastLoaded != null && lastLoaded.kind.isContainer()) loadStack.add(lastLoaded.children);
            else loadStack.add(new ArrayList<>());          // shouldn't happen; read and throw away
            return;
        }
        if (parts.length == 1 && parts[0].equals("}")) {
            if (loadStack.size() > 1) loadStack.remove(loadStack.size() - 1);
            return;
        }

        int id = -1, link = -1;
        if (parts.length == 8 && parts[0].equals("step")) {   // newest format
            id = Integer.parseInt(parts[6]);
            link = Integer.parseInt(parts[7]);
            parts = java.util.Arrays.copyOfRange(parts, 1, 6);
        }
        if (parts.length == 2 && parts[0].equals("repeat")) {
            legacyRepeat = clamp(Integer.parseInt(parts[1]), 0, 100000);
        } else if (parts.length >= 3 && parts.length <= 5 && target.size() < MAX_STEPS) {
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
            } else if (kind == Kind.REPEAT) {
                a = clamp(a, 0, 100000);
            } else if (kind != Kind.IF) {
                a = clamp(a, 1, MAX_AMOUNT);
            }
            Step st = new Step(kind, Dir.valueOf(parts[1]), a, b, c);
            if (id > 0) {
                st.id = id;
                nextId = Math.max(nextId, id + 1);
            }
            st.link = link;
            target.add(st);
            lastLoaded = st;
        }
    }

    /** Called after the last line: turns older flat saves into the nested layout. */
    void finishLoading() {
        if (!sawBraces) {
            // old "if" blocks controlled just the next block: put that block inside them
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                if ((s.kind == Kind.IF || s.kind == Kind.IF_AT) && s.children.isEmpty() && i + 1 < steps.size()) {
                    s.children.add(steps.remove(i + 1));
                }
            }
            // old scripts always looped: wrap everything in a repeat block
            if (legacyRepeat >= 0 && !steps.isEmpty()) {
                Step loop = new Step(Kind.REPEAT, Dir.LEFT, legacyRepeat);
                loop.children.addAll(steps);
                steps.clear();
                steps.add(loop);
            }
        }
        loadStack.clear();
        lastLoaded = null;
    }
}
