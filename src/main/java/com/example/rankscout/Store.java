package com.example.rankscout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What's been scanned, kept in .minecraft/rankscout/data.json, and the report (ranks.html).
 * Per stat: a player's value is only kept when it beats yours.
 */
public final class Store {

    private Store() {
    }

    public static final class Profile {
        public String name;
        public long scanned;                                   // epoch millis
        public Map<String, Parse.Stat> skills = new LinkedHashMap<>();
        public Map<String, Parse.Stat> collections = new LinkedHashMap<>();
    }

    public static final class Data {
        public Profile me;
        public Map<String, Profile> players = new LinkedHashMap<>();
        /** Name -> last time we looked at them (also for players who had nothing above you). */
        public Map<String, Long> lastScan = new LinkedHashMap<>();
    }

    public static Data data = new Data();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("rankscout");
    }

    public static Path html() {
        return dir().resolve("ranks.html");
    }

    public static void load() {
        Path f = dir().resolve("data.json");
        if (!Files.exists(f)) return;
        try {
            Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<Data>() {}.getType());
            if (d != null) {
                if (d.players == null) d.players = new LinkedHashMap<>();
                if (d.lastScan == null) d.lastScan = new LinkedHashMap<>();
                data = d;
            }
        } catch (Exception e) {
            RankScoutClient.LOG.warn("Couldn't read data.json", e);
        }
    }

    /** Your own stats: replaces "me", then drops everything others no longer beat you in. */
    public static void setMe(Profile me) {
        data.me = me;
        for (Iterator<Map.Entry<String, Profile>> it = data.players.entrySet().iterator(); it.hasNext(); ) {
            Profile p = it.next().getValue();
            keepOnlyAbove(p);
            if (p.skills.isEmpty() && p.collections.isEmpty()) it.remove();
        }
        save();
    }

    /** Another player's stats: keep only what's above you; forget them entirely if nothing is. Returns stats kept. */
    public static int addPlayer(Profile p) {
        data.lastScan.put(p.name.toLowerCase(), p.scanned);
        keepOnlyAbove(p);
        int kept = p.skills.size() + p.collections.size();
        if (kept == 0) data.players.remove(p.name.toLowerCase());
        else data.players.put(p.name.toLowerCase(), p);
        save();
        return kept;
    }

    private static void keepOnlyAbove(Profile p) {
        if (data.me == null) return;                           // nothing to compare with yet: keep for now
        p.skills.entrySet().removeIf(e -> !e.getValue().better(data.me.skills.get(e.getKey()))
                && data.me.skills.containsKey(e.getKey()));
        p.collections.entrySet().removeIf(e -> !e.getValue().better(data.me.collections.get(e.getKey()))
                && data.me.collections.containsKey(e.getKey()));
    }

    public static long lastScan(String name) {
        return data.lastScan.getOrDefault(name.toLowerCase(), 0L);
    }

    public static void save() {
        try {
            Files.createDirectories(dir());
            if (data.lastScan.size() > 5000) data.lastScan.clear();
            Files.writeString(dir().resolve("data.json"), GSON.toJson(data), StandardCharsets.UTF_8);
            Files.writeString(html(), Report.build(data, GSON.toJson(data), Instant.now()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            RankScoutClient.LOG.warn("Couldn't save rankscout data", e);
        }
    }
}
