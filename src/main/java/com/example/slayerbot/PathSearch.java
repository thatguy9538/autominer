package com.example.slayerbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* search over block positions for walking: flat steps (also diagonal), 1-block
 * jumps up and drops of up to 3 blocks. It only knows about the world through
 * {@link World}, so it can be tested without Minecraft.
 */
public final class PathSearch {

    /** What the search needs to know about the world. */
    public interface World {
        /** Can the player's body be in this block (no collision, no liquid, loaded)? */
        boolean passable(int x, int y, int z);

        /** Can the player stand on top of this block? */
        boolean standable(int x, int y, int z);

        /** Lava, fire, cactus...: never walk in or onto it. */
        boolean dangerous(int x, int y, int z);
    }

    public record Pos(int x, int y, int z) {
        Pos add(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int MAX_DROP = 3;

    private PathSearch() {
    }

    /** Can the player stand with their feet in this block? */
    public static boolean walkable(World w, Pos p) {
        return w.passable(p.x, p.y, p.z) && w.passable(p.x, p.y + 1, p.z)
                && w.standable(p.x, p.y - 1, p.z)
                && !w.dangerous(p.x, p.y, p.z) && !w.dangerous(p.x, p.y + 1, p.z) && !w.dangerous(p.x, p.y - 1, p.z);
    }

    private static final class Node implements Comparable<Node> {
        final Pos pos;
        final double g, f;
        final Node parent;

        Node(Pos pos, double g, double f, Node parent) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    /**
     * Finds a walking path from start (feet position) to any position where goal is true.
     * If the goal can't be reached within maxNodes, returns the path to the explored position
     * closest to `toward` (so the caller can walk part of the way and search again), or null
     * if that wouldn't get any closer. The returned list excludes the start.
     */
    public static List<Pos> find(World w, Pos start, Predicate<Pos> goal, Pos toward, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Pos, Double> best = new HashMap<>();
        Node first = new Node(start, 0, h(start, toward), null);
        open.add(first);
        best.put(start, 0.0);
        Node closest = first;
        double closestH = h(start, toward);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE)) continue;   // stale entry
            if (goal.test(n.pos)) return build(n);
            expanded++;
            double nh = h(n.pos, toward);
            if (nh < closestH) {
                closest = n;
                closestH = nh;
            }
            for (int[] d : DIRS) {
                boolean diag = d[0] != 0 && d[1] != 0;
                Move m = move(w, n.pos, d[0], d[1], diag);
                if (m == null) continue;
                double g = n.g + m.cost + edgePenalty(w, m.to);
                if (g < best.getOrDefault(m.to, Double.MAX_VALUE)) {
                    best.put(m.to, g);
                    open.add(new Node(m.to, g, g + h(m.to, toward), n));
                }
            }
        }
        if (closest == first) return null;
        return build(closest);
    }

    private record Move(Pos to, double cost) {}

    private static Move move(World w, Pos p, int dx, int dz, boolean diag) {
        double base = diag ? 1.4142 : 1.0;
        if (diag) {
            // don't cut corners: both side blocks must be clear at feet and head height
            if (!w.passable(p.x + dx, p.y, p.z) || !w.passable(p.x + dx, p.y + 1, p.z)) return null;
            if (!w.passable(p.x, p.y, p.z + dz) || !w.passable(p.x, p.y + 1, p.z + dz)) return null;
        }
        Pos flat = p.add(dx, 0, dz);
        if (walkable(w, flat)) return new Move(flat, base);

        // jump up one block (straight moves only): need room above our head
        Pos up = p.add(dx, 1, dz);
        if (!diag && w.passable(p.x, p.y + 2, p.z) && walkable(w, up)) return new Move(up, base + 0.8);

        // drop down: the column in front must be clear down to the landing spot
        if (!w.passable(flat.x, flat.y, flat.z) || !w.passable(flat.x, flat.y + 1, flat.z)) return null;
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            Pos down = p.add(dx, -drop, dz);
            if (walkable(w, down)) return new Move(down, base + 0.3 * drop);
            if (!w.passable(down.x, down.y, down.z)) return null;   // hit something that isn't a floor
        }
        return null;
    }

    /**
     * Extra cost for standing next to a big drop or next to lava/fire, so paths keep away from
     * ledges instead of hugging them (where a small slip makes you fall).
     */
    private static double edgePenalty(World w, Pos p) {
        double extra = 0;
        for (int[] d : CARDINAL) {
            int x = p.x + d[0], z = p.z + d[1];
            if (w.dangerous(x, p.y, z) || w.dangerous(x, p.y - 1, z)) {
                extra += 1.5;
                continue;
            }
            if (w.passable(x, p.y, z) && w.passable(x, p.y - 1, z) && w.passable(x, p.y - 2, z)
                    && !w.standable(x, p.y - 1, z)) {
                extra += 0.4;                              // open drop of 2+ blocks right beside us
            }
        }
        return Math.min(extra, 2.0);
    }

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Distance estimate: horizontal octile distance plus the height difference. */
    private static double h(Pos a, Pos b) {
        int dx = Math.abs(a.x - b.x), dz = Math.abs(a.z - b.z), dy = Math.abs(a.y - b.y);
        return Math.max(dx, dz) + 0.4142 * Math.min(dx, dz) + dy * 0.8;
    }

    private static List<Pos> build(Node end) {
        List<Pos> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        return out;
    }
}
