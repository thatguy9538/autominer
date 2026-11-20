package com.example.visitorwaypoints;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searches the island for visitor NPCs: every second it reads the nametags of all entities your
 * game has loaded and remembers where each visitor's NPC really stands (per island), so the
 * waypoint goes to the actual spot, even where the wiki's coordinates are off or missing.
 * The server only sends entities near you, so the island fills in as you move around.
 */
public final class Scanner {

    private Scanner() {
    }

    private static int ticks = 0;

    public static void tick(MinecraftClient client) {
        if (++ticks % 20 != 0 || client.world == null || client.player == null) return;
        String island = Where.island;
        // visitors also show up *in the Garden*: never record those spots
        if (island == null || island.equalsIgnoreCase("Garden") || island.equalsIgnoreCase("Private Island")) return;
        boolean changed = false;
        for (Entity e : client.world.getEntities()) {
            if (e == client.player) continue;
            Text custom = e.getCustomName();
            if (custom == null) continue;
            String name = Formatting.strip(custom.getString());
            if (name == null || name.isBlank()) continue;
            name = name.replaceAll("^\\[NPC\\]\\s*", "").trim();
            Visitors.Visitor v = Visitors.find(name);
            if (v == null || !needsTrip(v)) continue;
            // nametag armor stands float above the NPC's head
            double y = e instanceof ArmorStandEntity ? e.getY() - 1.9 : e.getY();
            if (Visitors.addSpot(v, island, e.getX(), y, e.getZ())) {
                changed = true;
                Visitors.State st = Visitors.STATE.get(v.name());
                boolean wanted = st != null && st.seen && !st.unlocked && !st.talked;
                if (wanted) {
                    VisitorWaypointsClient.say(client, "Spotted " + v.name() + " at " + (int) Math.floor(e.getX())
                            + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(e.getZ()) + " on " + island);
                }
            }
        }
        if (changed) Visitors.save();
    }

    /** Visitors you have to go somewhere for (not only a Garden level, not Mayors). */
    static boolean needsTrip(Visitors.Visitor v) {
        String r = v.requirement().toLowerCase(Locale.ROOT);
        return !r.isEmpty() && !r.startsWith("only visits when elected") && !r.contains("blooming business");
    }

    /** The spots seen for a visitor on an island. */
    static List<Visitors.Spot> spots(Visitors.Visitor v, String island) {
        List<Visitors.Spot> out = new ArrayList<>();
        for (Visitors.Spot s : Visitors.FOUND.getOrDefault(v.name(), List.of())) {
            if (s.island.equalsIgnoreCase(island)) out.add(s);
        }
        return out;
    }
}
