package com.example.ghostbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The whole of The Mist as the fighting area. There's no fixed map: the sidebar's location line says
 * whether you're in The Mist, so the bot learns the area as it walks (8x8-block cells it has stood in
 * while the sidebar said "The Mist", and cells where it stopped saying so). When nothing is in sight,
 * it heads for places it recently saw ghosts, otherwise explores the parts of the Mist it hasn't
 * checked for the longest time. If it ever walks out, it turns around and goes back to the last spot
 * that was in The Mist.
 */
public final class Area {

    private Area() {
    }

    private static final int CELL = 8;
    /** Cells (x,z packed) we've stood in while in The Mist, and when we were last there. */
    private static final Map<Long, Long> mist = new HashMap<>();
    /** Cells where the sidebar said we'd left The Mist. */
    private static final Set<Long> outside = new HashSet<>();
    /** Where ghosts were last seen (they drop out of view past render distance). */
    private static final Map<UUID, Sighting> sightings = new HashMap<>();

    private record Sighting(Vec3d pos, long at) {
    }

    /** Does the sidebar say The Mist right now (read every half second)? */
    public static boolean inMist = false;
    /** We were in The Mist and now aren't: go back. */
    public static boolean left = false;
    private static Vec3d lastMistPos = null;
    private static double mistY = Double.NaN;
    private static int ticks = 0;

    public static void reset() {
        sightings.clear();
        left = false;
        ticks = 0;
    }

    public static void tick(MinecraftClient client) {
        if (++ticks % 10 != 0 || client.player == null) return;
        Vec3d p = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        inMist = sidebarSaysMist(client);
        long c = cell(p);
        long now = System.currentTimeMillis();
        if (inMist) {
            mist.put(c, now);
            outside.remove(c);
            lastMistPos = p;
            mistY = Double.isNaN(mistY) ? p.y : mistY * 0.9 + p.y * 0.1;
            left = false;
        } else if (lastMistPos != null && GhostBotClient.mistArea) {
            outside.add(c);
            left = true;
        }
        sightings.values().removeIf(s -> now - s.at > 90_000);
    }

    /** Remember where these ghosts are. */
    public static void saw(List<Entity> ghosts) {
        long now = System.currentTimeMillis();
        for (Entity e : ghosts) sightings.put(e.getUuid(), new Sighting(new Vec3d(e.getX(), e.getY(), e.getZ()), now));
        if (sightings.size() > 80) {
            Iterator<Map.Entry<UUID, Sighting>> it = sightings.entrySet().iterator();
            while (sightings.size() > 80 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** Is this spot allowed? Anything not known to be outside The Mist. */
    public static boolean allowed(Vec3d pos) {
        return !outside.contains(cell(pos));
    }

    public static Vec3d lastMistPos() {
        return lastMistPos;
    }

    /** Where to walk next while nothing is in sight, or null if there's nowhere sensible. */
    public static Vec3d nextGoal(MinecraftClient client) {
        Vec3d me = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        if (left && lastMistPos != null) return lastMistPos;
        long now = System.currentTimeMillis();

        // 1. a ghost we saw recently but lost sight of (not right here, or we'd see it)
        Vec3d bestSeen = null;
        double bestScore = Double.MAX_VALUE;
        for (Sighting s : sightings.values()) {
            double d = s.pos.distanceTo(me);
            if (d < 14 || d > 120 || !allowed(s.pos)) continue;
            double score = d + (now - s.at) / 1000.0 * 0.5;      // nearer and fresher first
            if (score < bestScore) {
                bestScore = score;
                bestSeen = s.pos;
            }
        }
        if (bestSeen != null) {
            // the ghost has moved on; aim for its spot and drop it so we don't keep coming back
            Vec3d goal = bestSeen;
            sightings.values().removeIf(s -> s.pos.squaredDistanceTo(goal) < 100);
            return standable(client, goal);
        }

        // 2. explore: try random spots 16-45 blocks away, prefer cells not visited for the longest
        Vec3d best = null;
        double bestStale = -1;
        for (int i = 0; i < 14; i++) {
            double ang = Math.random() * Math.PI * 2, rad = 16 + Math.random() * 29;
            Vec3d g = new Vec3d(me.x + Math.cos(ang) * rad, Double.isNaN(mistY) ? me.y : mistY, me.z + Math.sin(ang) * rad);
            if (!allowed(g)) continue;
            Vec3d s = standable(client, g);
            if (s == null) continue;
            Long last = mist.get(cell(s));
            double stale = last == null ? 600 : (now - last) / 1000.0;   // never-visited counts as very stale
            if (stale > bestStale) {
                bestStale = stale;
                best = s;
            }
        }
        return best;
    }

    /** A spot you can stand on in this column, near the given height, or null. */
    private static Vec3d standable(MinecraftClient client, Vec3d g) {
        World w = client.world;
        int x = (int) Math.floor(g.x), z = (int) Math.floor(g.z), y0 = (int) Math.floor(g.y);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int dy = 0; dy <= 10; dy++) {
            for (int sign : new int[]{1, -1}) {
                int y = y0 + dy * sign;
                BlockPos feet = new BlockPos(x, y, z);
                if (!w.getBlockState(feet.down()).isSolidBlock(w, feet.down())) continue;
                if (!w.getBlockState(feet).getCollisionShape(w, feet).isEmpty()) continue;
                if (!w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty()) continue;
                return new Vec3d(x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    public static int cellsKnown() {
        return mist.size();
    }

    private static long cell(Vec3d p) {
        long cx = Math.floorDiv((long) Math.floor(p.x), CELL), cz = Math.floorDiv((long) Math.floor(p.z), CELL);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    static boolean sidebarSaysMist(MinecraftClient client) {
        if (client.world == null) return false;
        var sb = client.world.getScoreboard();
        var obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return false;
        for (var e : sb.getScoreboardEntries(obj)) {
            Team team = sb.getScoreHolderTeam(e.owner());
            String line = Formatting.strip(Team.decorateName(team, e.name()).getString());
            if (line != null && line.toLowerCase(Locale.ROOT).contains("the mist")) return true;
        }
        return false;
    }

    /** For the HUD. */
    public static String describe() {
        if (left) return "Left The Mist - heading back";
        return (inMist ? "In The Mist" : "Not in The Mist") + ", " + mist.size() + " spots mapped, " + sightings.size() + " ghosts remembered";
    }
}
