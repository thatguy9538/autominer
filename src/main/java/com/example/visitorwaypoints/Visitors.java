package com.example.visitorwaypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The visitor list (from the Hypixel SkyBlock Wiki, shipped in the jar) and what we've learned
 * about each one from your Visitor's Logbook (saved between games).
 */
public final class Visitors {

    private Visitors() {
    }

    public record Waypoint(String island, String zone, double x, double y, double z) {}

    public record Visitor(String name, String rarity, int gardenLevel, String requirement, List<Waypoint> waypoints) {}

    /** What the logbook said about a visitor. */
    public static final class State {
        public boolean seen;          // found in the logbook
        public boolean unlocked;      // visited before, or every requirement ticked
        public int visits = -1;       // -1 = the logbook didn't say
        public Boolean levelOk;       // the logbook's Garden level line: true ✔, false ✖, null = no line
        public boolean talked;        // you talked to the NPC since (chat "[NPC] Name:")
    }

    public static final List<Visitor> ALL = new ArrayList<>();
    private static final Map<String, Visitor> BY_KEY = new LinkedHashMap<>();
    public static final Map<String, State> STATE = new LinkedHashMap<>();
    /** Every zone name in the data -> its island (for working out the island from the sidebar). */
    public static final Map<String, String> ZONE_ISLAND = new LinkedHashMap<>();

    /** A spot where we saw a visitor's NPC ourselves. */
    public static final class Spot {
        public String island;
        public double x, y, z;
    }

    /** Visitor name -> spots seen by searching the islands (saved). */
    public static final Map<String, List<Spot>> FOUND = new LinkedHashMap<>();

    /** Remembers a spot; false if we already had one within 4 blocks (then it's just nudged). */
    public static boolean addSpot(Visitor v, String island, double x, double y, double z) {
        List<Spot> list = FOUND.computeIfAbsent(v.name(), n -> new ArrayList<>());
        for (Spot s : list) {
            if (s.island.equalsIgnoreCase(island)
                    && (s.x - x) * (s.x - x) + (s.y - y) * (s.y - y) + (s.z - z) * (s.z - z) < 16) {
                s.x = x;
                s.y = y;
                s.z = z;
                return false;
            }
        }
        if (list.size() >= 8) list.remove(0);
        Spot s = new Spot();
        s.island = island;
        s.x = x;
        s.y = y;
        s.z = z;
        list.add(s);
        return true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path stateFile;

    public static void load() {
        try (InputStream in = Visitors.class.getResourceAsStream("/assets/visitorwaypoints/visitors.json")) {
            JsonObject doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : doc.getAsJsonArray("visitors")) {
                JsonObject o = e.getAsJsonObject();
                List<Waypoint> wps = new ArrayList<>();
                for (JsonElement w : o.getAsJsonArray("waypoints")) {
                    JsonObject p = w.getAsJsonObject();
                    Waypoint wp = new Waypoint(p.get("island").getAsString(), p.get("zone").getAsString(),
                            p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
                    wps.add(wp);
                    ZONE_ISLAND.put(key(wp.zone()), wp.island());
                }
                Visitor v = new Visitor(o.get("name").getAsString(), o.get("rarity").getAsString(),
                        o.get("gardenLevel").getAsInt(), o.get("requirement").getAsString(), List.copyOf(wps));
                ALL.add(v);
                BY_KEY.put(key(v.name()), v);
            }
        } catch (Exception e) {
            VisitorWaypointsClient.LOG.error("Couldn't read visitors.json", e);
        }
        stateFile = FabricLoader.getInstance().getConfigDir().resolve("visitorwaypoints-logbook.json");
        loadState();
    }

    /** Names compared without colors, case, spaces or punctuation. */
    public static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The visitor with this (item / NPC) name, or null. Tolerates prefixes like "Emissary" being left off. */
    public static Visitor find(String name) {
        String k = key(name);
        if (k.isEmpty()) return null;
        Visitor v = BY_KEY.get(k);
        if (v != null) return v;
        for (Map.Entry<String, Visitor> e : BY_KEY.entrySet()) {
            if (e.getKey().endsWith(k) && k.length() >= 4) return e.getValue();   // "Carlton" -> "Emissary Carlton"
        }
        return null;
    }

    public static State state(Visitor v) {
        return STATE.computeIfAbsent(v.name(), n -> new State());
    }

    // ------------------------------------------------------------------ saving

    private static void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("gardenLevel")) VisitorWaypointsClient.gardenLevel = o.get("gardenLevel").getAsInt();
            if (o.has("enabled")) VisitorWaypointsClient.enabled = o.get("enabled").getAsBoolean();
            if (o.has("tracers")) VisitorWaypointsClient.tracers = o.get("tracers").getAsBoolean();
            JsonObject vis = o.getAsJsonObject("visitors");
            JsonObject found = o.getAsJsonObject("found");
            if (found != null) {
                for (String name : found.keySet()) {
                    List<Spot> list = new ArrayList<>();
                    for (JsonElement e : found.getAsJsonArray(name)) list.add(GSON.fromJson(e, Spot.class));
                    FOUND.put(name, list);
                }
            }
            if (vis != null) {
                for (String name : vis.keySet()) STATE.put(name, GSON.fromJson(vis.get(name), State.class));
            }
        } catch (Exception e) {
            VisitorWaypointsClient.LOG.warn("Couldn't read {}", stateFile, e);
        }
    }

    public static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("gardenLevel", VisitorWaypointsClient.gardenLevel);
        o.addProperty("enabled", VisitorWaypointsClient.enabled);
        o.addProperty("tracers", VisitorWaypointsClient.tracers);
        o.add("visitors", GSON.toJsonTree(STATE));
        o.add("found", GSON.toJsonTree(FOUND));
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            VisitorWaypointsClient.LOG.warn("Couldn't save {}", stateFile, e);
        }
    }

    public static void reset() {
        STATE.clear();
        save();
    }

    /** Unused helper kept small: list of names in the data (for /vw find). */
    public static JsonArray names() {
        JsonArray a = new JsonArray();
        for (Visitor v : ALL) a.add(v.name());
        return a;
    }
}
