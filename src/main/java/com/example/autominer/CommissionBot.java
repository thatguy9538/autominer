package com.example.autominer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does Dwarven Mines commissions by itself: reads them from the tab list, walks to the
 * area, mines (using Auto Miner) or fights, and claims finished ones with the Royal Pigeon
 * or at a claim NPC.
 */
public final class CommissionBot {

    private CommissionBot() {
    }

    // ------------------------------------------------------------------ areas and blocks

    /** Places you can set in the menu. */
    public static final List<String> AREAS = List.of(
            "Upper Mines", "Royal Mines", "Lava Springs", "Cliffside Veins", "Rampart's Quarry",
            "Goblin Burrows", "Great Ice Wall", "Claim NPC");
    public static final Map<String, BlockPos> locations = new LinkedHashMap<>();

    private static final Set<String> MITHRIL = Set.of(
            "minecraft:gray_wool", "minecraft:cyan_terracotta", "minecraft:prismarine",
            "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:light_blue_wool");
    private static final Set<String> TITANIUM_ONLY = Set.of("minecraft:polished_diorite");
    /** Titanium only appears when you mine mithril, so titanium commissions mine both (titanium first). */
    private static final Set<String> TITANIUM;
    static {
        java.util.HashSet<String> t = new java.util.HashSet<>(MITHRIL);
        t.addAll(TITANIUM_ONLY);
        TITANIUM = java.util.Collections.unmodifiableSet(t);
    }

    private enum Kind { MINE_MITHRIL, MINE_TITANIUM, KILL, UNSUPPORTED }

    private record Task(String commission, Kind kind, String area, List<String> mobs) {}

    public record Commission(String name, String progress, boolean done) {}

    // ------------------------------------------------------------------ state

    private enum State { OFF, READ, TRAVEL, MINE, FIGHT, CLAIM_SELECT, CLAIM_OPEN, CLAIM_CLICK, CLAIM_NPC, WAIT }

    public static boolean running = false;
    public static String status = "Off";
    private static State state = State.OFF;
    private static Task task = null;
    private static Walker walker = null;
    private static State afterTravel = State.READ;
    private static Runnable onArrive = null;
    private static int timer = 0, idleTicks = 0, attackCooldown = 0, claimTries = 0, travelFails = 0;
    private static long tick = 0;
    private static List<Commission> commissions = new ArrayList<>();
    private static Map<String, AutoMinerClient.Target> savedTargets = null;
    /** True while the bot has swapped Auto Miner's targets (so they aren't saved). */
    public static boolean usingTargets = false;
    private static String lastShown = "";
    /** Hotbar slot you had selected before switching to the pigeon (-1 = none). */
    private static int slotBeforeClaim = -1;
    private static boolean warnedNoClaim = false;
    /** Ores we walked to but couldn't mine (not visible / unreachable), skipped until the given tick. */
    private static final Map<BlockPos, Long> triedOres = new java.util.HashMap<>();
    private static final int TRIED_TICKS = 1200;          // 60 s
    /** The ore we're walking to (or just walked to), so it can be marked as tried if it doesn't work out. */
    private static BlockPos lastOre = null;

    private static KeyBinding toggleKey;
    private static Path configFile;

    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z0-9' ]+?):\\s*(DONE|[0-9.]+%)\\s*$");
    private static final String[] COMMISSION_WORDS = {"miner", "mithril", "titanium", "slayer", "puncher",
            "collector", "hoarder", "goblin", "walker", "sentry", "raffle", "raid", "gemstone", "glacite"};

    public static void init() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("autominer-commissions.txt");
        load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.commissions", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K,
                KeyBinding.Category.create(net.minecraft.util.Identifier.of(AutoMinerClient.MOD_ID, "commissions"))));
        ClientTickEvents.END_CLIENT_TICK.register(CommissionBot::onTick);
    }

    public static void setRunning(MinecraftClient client, boolean on) {
        if (on == running) return;
        running = on;
        if (on) {
            state = State.READ;
            claimTries = 0;
            travelFails = 0;
            warnedNoClaim = false;
            show(client, "Commissions: started");
        } else {
            stopWork(client);
            state = State.OFF;
            status = "Off";
            show(client, "Commissions: stopped");
        }
    }

    // ------------------------------------------------------------------ tick

    private static void onTick(MinecraftClient client) {
        tick++;
        while (toggleKey.wasPressed()) setRunning(client, !running);
        if (!running) return;
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            running = false;
            state = State.OFF;
            usingTargets = false;
            return;
        }
        if (player.isDead()) {
            stopWork(client);
            state = State.WAIT;
            timer = 60;
            setStatus(client, "You died, waiting...");
            return;
        }
        boolean claimScreen = state == State.CLAIM_OPEN || state == State.CLAIM_CLICK;
        if (client.currentScreen != null && !claimScreen) {          // some other menu is open: pause
            if (walker != null) walker.stop(client);
            return;
        }
        if (tick % 20 == 0) commissions = readCommissions(client);
        if (attackCooldown > 0) attackCooldown--;

        switch (state) {
            case READ -> decide(client);
            case WAIT -> {
                if (--timer <= 0) state = State.READ;
            }
            case TRAVEL -> travel(client);
            case MINE -> mine(client);
            case FIGHT -> fight(client, player, world);
            case CLAIM_SELECT, CLAIM_OPEN, CLAIM_CLICK, CLAIM_NPC -> claim(client, player, world);
            default -> { }
        }
        if (tick % 40 == 0) show(client, status);
    }

    // ------------------------------------------------------------------ deciding what to do

    private static void decide(MinecraftClient client) {
        List<Commission> list = readCommissions(client);
        commissions = list;
        if (list.isEmpty()) {
            setStatus(client, "No commissions in the tab list. Go to the Dwarven Mines.");
            waitFor(60);
            return;
        }
        for (Commission c : list) {
            if (c.done()) {
                startClaim(client);
                return;
            }
        }
        String missing = null;
        for (Commission c : list) {
            Task t = taskFor(c.name());
            if (t.kind() == Kind.UNSUPPORTED) continue;
            if (t.area() != null && !locations.containsKey(t.area())) {
                if (missing == null) missing = t.area();
                continue;
            }
            task = t;
            startTask(client);
            return;
        }
        if (missing != null) {
            setStatus(client, "Set a location for \"" + missing + "\" in the Commissions menu (O > Commissions).");
        } else {
            setStatus(client, "None of your commissions can be done automatically. Waiting...");
        }
        waitFor(100);
    }

    private static Task taskFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String area = null;
        for (String a : AREAS) if (n.contains(a.toLowerCase(Locale.ROOT))) area = a;
        if (n.contains("slayer") || n.contains("puncher")) {
            if (n.contains("goblin") && !n.contains("golden") && !n.contains("raid")) {
                return new Task(name, Kind.KILL, "Goblin Burrows", List.of("Goblin", "Knifethrower", "Fireslinger", "Weakling"));
            }
            if (n.contains("walker")) return new Task(name, Kind.KILL, "Great Ice Wall", List.of("Glacite Walker", "Ice Walker"));
            if (n.contains("treasure")) return new Task(name, Kind.KILL, null, List.of("Treasure Hoarder"));
            if (n.contains("sentry")) return new Task(name, Kind.KILL, null, List.of("Star Sentry", "Crystal Sentry"));
            return new Task(name, Kind.UNSUPPORTED, null, List.of());
        }
        if (n.contains("powder") || n.contains("collector") || n.contains("raffle") || n.contains("raid")) {
            return new Task(name, Kind.UNSUPPORTED, null, List.of());
        }
        if (n.contains("mithril")) return new Task(name, Kind.MINE_MITHRIL, area, List.of());
        if (n.contains("titanium")) return new Task(name, Kind.MINE_TITANIUM, area, List.of());
        return new Task(name, Kind.UNSUPPORTED, null, List.of());
    }

    private static void startTask(MinecraftClient client) {
        BlockPos home = task.area() != null ? locations.get(task.area()) : null;
        State work = task.kind() == Kind.KILL ? State.FIGHT : State.MINE;
        if (home != null && client.player.getBlockPos().getSquaredDistance(home) > 12 * 12) {
            travelTo(client, home, 3.0, work, "Walking to " + task.area() + " for " + task.commission());
        } else {
            beginWork(client, work);
        }
    }

    private static void beginWork(MinecraftClient client, State work) {
        idleTicks = 0;
        if (work == State.MINE) {
            useMinerTargets(client, task.kind() == Kind.MINE_TITANIUM ? TITANIUM : MITHRIL);
            setStatus(client, "Mining for " + task.commission() + progressOf(task.commission()));
        } else {
            setStatus(client, "Fighting for " + task.commission() + progressOf(task.commission()));
        }
        state = work;
    }

    // ------------------------------------------------------------------ travel

    private static void travelTo(MinecraftClient client, BlockPos target, double radius, State next, String why) {
        stopMiner(client);
        walker = Walker.to(target, radius);
        afterTravel = next;
        onArrive = null;
        state = State.TRAVEL;
        setStatus(client, why);
    }

    private static void travel(MinecraftClient client) {
        if (walker == null) {
            state = State.READ;
            return;
        }
        Walker.Result r = walker.tick(client);
        if (r == Walker.Result.WALKING) return;
        walker.stop(client);
        walker = null;
        if (r == Walker.Result.FAILED) {
            markOreTried();                                // couldn't get to that ore: skip it for a while
            if (++travelFails >= 3) {
                setStatus(client, "Can't find a way there. Move closer, or set the location somewhere easier to reach.");
                travelFails = 0;
                waitFor(200);
            } else {
                waitFor(20);
            }
            return;
        }
        travelFails = 0;
        if (onArrive != null) {
            Runnable a = onArrive;
            onArrive = null;
            a.run();
        } else if (afterTravel == State.MINE || afterTravel == State.FIGHT) {
            beginWork(client, afterTravel);
        } else {
            state = afterTravel;
        }
    }

    // ------------------------------------------------------------------ mining

    private static void mine(MinecraftClient client) {
        if (anyDone()) {
            stopMiner(client);
            startClaim(client);
            return;
        }
        if (!stillHave(task.commission())) {                 // commission changed (claimed elsewhere?)
            stopMiner(client);
            state = State.READ;
            return;
        }
        if (tick % 40 == 0) setStatus(client, "Mining for " + task.commission() + progressOf(task.commission()));
        if (!AutoMinerClient.isIdle()) {
            idleTicks = 0;
            lastOre = null;                                // it's mining: that spot worked
            return;
        }
        if (++idleTicks < 40) return;                       // nothing in reach for 2 s: go find more
        idleTicks = 0;
        markOreTried();                                    // the ore we came for didn't work out
        Set<String> blocks = task.kind() == Kind.MINE_TITANIUM ? TITANIUM : MITHRIL;
        BlockPos home = task.area() != null ? locations.get(task.area()) : null;
        // titanium spawns from mithril, so look for either; mithril is also fine for titanium commissions
        BlockPos ore = findOre(client, blocks, home);
        if (ore != null) {
            stopMiner(client);
            lastOre = ore;
            ClientWorld world = client.world;
            ClientPlayerEntity player = client.player;
            // stop somewhere close enough AND with a clear view of the ore, so Auto Miner can mine it
            walker = new Walker(p -> eyeDistance(p, ore) <= 3.6 && canSeeFrom(world, player, p, ore), ore);
            afterTravel = State.MINE;
            onArrive = () -> beginWork(client, State.MINE);
            state = State.TRAVEL;
            setStatus(client, "Walking to more " + (blocks == TITANIUM ? "titanium" : "mithril"));
        } else if (home != null && client.player.getBlockPos().getSquaredDistance(home) > 6 * 6) {
            lastOre = null;
            travelTo(client, home, 3.0, State.MINE, "No ore nearby, going back to " + task.area());
        } else {
            setStatus(client, "No " + (blocks == TITANIUM ? "titanium" : "mithril") + " nearby, waiting for it to come back...");
        }
    }

    private static double eyeDistance(BlockPos feet, BlockPos block) {
        double dx = feet.getX() + 0.5 - (block.getX() + 0.5);
        double dy = feet.getY() + 1.62 - (block.getY() + 0.5);
        double dz = feet.getZ() + 0.5 - (block.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The closest exposed ore block within ~24 blocks (and within 40 of the area, if it has one). */
    private static BlockPos findOre(MinecraftClient client, Set<String> blocks, BlockPos home) {
        triedOres.values().removeIf(until -> until < tick);
        ClientWorld world = client.world;
        BlockPos me = client.player.getBlockPos();
        BlockPos.Mutable p = new BlockPos.Mutable();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -24; x <= 24; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -24; z <= 24; z++) {
                    p.set(me.getX() + x, me.getY() + y, me.getZ() + z);
                    double d = p.getSquaredDistance(me);
                    if (d >= bestD) continue;
                    if (triedOres.containsKey(p)) continue;    // already tried and couldn't mine it
                    if (home != null && p.getSquaredDistance(home) > 40 * 40) continue;
                    if (!blocks.contains(Registries.BLOCK.getId(world.getBlockState(p).getBlock()).toString())) continue;
                    if (!exposed(world, p)) continue;
                    best = p.toImmutable();
                    bestD = d;
                }
            }
        }
        return best;
    }

    private static void markOreTried() {
        if (lastOre != null) {
            triedOres.put(lastOre, tick + TRIED_TICKS);
            lastOre = null;
        }
    }

    /** Could you see the ore from standing at `feet`? Checks the middle and the middle of each face. */
    private static boolean canSeeFrom(ClientWorld world, ClientPlayerEntity player, BlockPos feet, BlockPos ore) {
        Vec3d eye = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        Vec3d c = Vec3d.ofCenter(ore);
        List<Vec3d> points = new ArrayList<>();
        points.add(c);
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
            points.add(c.add(d.getOffsetX() * 0.49, d.getOffsetY() * 0.49, d.getOffsetZ() * 0.49));
        }
        for (Vec3d pt : points) {
            net.minecraft.util.hit.BlockHitResult hit = world.raycast(new net.minecraft.world.RaycastContext(
                    eye, pt, net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && hit.getBlockPos().equals(ore)) return true;
        }
        return false;
    }

    private static boolean exposed(ClientWorld world, BlockPos p) {
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
            BlockPos n = p.offset(d);
            if (world.getBlockState(n).getCollisionShape(world, n).isEmpty()) return true;
        }
        return false;
    }

    private static void useMinerTargets(MinecraftClient client, Set<String> blocks) {
        if (savedTargets == null) savedTargets = new LinkedHashMap<>(AutoMinerClient.targets);
        usingTargets = true;
        AutoMinerClient.targets.clear();
        for (String id : blocks) {
            int prio = TITANIUM_ONLY.contains(id) ? 5                 // titanium whenever it shows up
                    : id.endsWith("light_blue_wool") ? 4 : AutoMinerClient.DEFAULT_PRIORITY;   // best mithril first
            AutoMinerClient.targets.put(id, new AutoMinerClient.Target(id.substring(10).replace('_', ' '), prio));
        }
        AutoMinerClient.setEnabled(client, true);
    }

    private static void stopMiner(MinecraftClient client) {
        if (AutoMinerClient.enabled) AutoMinerClient.setEnabled(client, false);
        if (savedTargets != null) {
            AutoMinerClient.targets.clear();
            AutoMinerClient.targets.putAll(savedTargets);
            savedTargets = null;
        }
        usingTargets = false;
    }

    // ------------------------------------------------------------------ fighting

    private static void fight(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        if (anyDone()) {
            stopWalking(client);
            startClaim(client);
            return;
        }
        if (!stillHave(task.commission())) {
            stopWalking(client);
            state = State.READ;
            return;
        }
        BlockPos home = task.area() != null ? locations.get(task.area()) : null;
        LivingEntity mob = findMob(world, player, task.mobs(), 32, home);
        if (mob == null) {
            stopWalking(client);
            if (home != null && player.getBlockPos().getSquaredDistance(home) > 8 * 8) {
                travelTo(client, home, 3.0, State.FIGHT, "No mobs here, going back to " + task.area());
            } else {
                setStatus(client, "Looking for " + String.join(" / ", task.mobs()) + "..." + progressOf(task.commission()));
            }
            return;
        }
        double dist = player.getEyePos().distanceTo(mob.getBoundingBox().getCenter());
        if (dist > 3.0) {
            // walk toward it; re-plan every second because it moves
            if (walker == null || tick % 20 == 0) {
                if (walker != null) walker.stop(client);
                BlockPos at = mob.getBlockPos();
                walker = Walker.to(at, 2.2);
            }
            if (walker.tick(client) == Walker.Result.FAILED) walker = null;
            return;
        }
        stopWalking(client);
        Vec3d aim = mob.getBoundingBox().getCenter();
        AutoMinerClient.aimAt(player, aim);
        Vec3d look = player.getRotationVec(1f);
        Vec3d to = aim.subtract(player.getEyePos()).normalize();
        if (attackCooldown <= 0 && look.dotProduct(to) > 0.95) {   // roughly looking at it
            client.interactionManager.attackEntity(player, mob);
            player.swingHand(Hand.MAIN_HAND);
            attackCooldown = 10;
        }
        if (tick % 40 == 0) setStatus(client, "Fighting for " + task.commission() + progressOf(task.commission()));
    }

    private static void stopWalking(MinecraftClient client) {
        if (walker != null) {
            walker.stop(client);
            walker = null;
        }
    }

    /** A living mob whose own name, or the nametag floating above it, contains one of the names. */
    private static LivingEntity findMob(ClientWorld world, ClientPlayerEntity player, List<String> names, double range,
                                        BlockPos home) {
        List<Vec3d> tags = new ArrayList<>();
        for (Entity e : world.getEntities()) {
            if (e instanceof ArmorStandEntity && nameMatches(e, names)) tags.add(new Vec3d(e.getX(), e.getY(), e.getZ()));
        }
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStandEntity || e == player) continue;
            if (!le.isAlive() || le.getHealth() <= 0) continue;
            double d = e.squaredDistanceTo(player);
            if (d >= bestD) continue;
            if (home != null && e.getBlockPos().getSquaredDistance(home) > 40 * 40) continue;
            boolean match = nameMatches(e, names);
            if (!match) {
                for (Vec3d t : tags) {
                    double dx = t.x - e.getX(), dz = t.z - e.getZ(), dy = t.y - e.getY();
                    if (dx * dx + dz * dz < 1.2 && dy > -0.5 && dy < 3.5) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                best = le;
                bestD = d;
            }
        }
        return best;
    }

    private static boolean nameMatches(Entity e, List<String> names) {
        if (!e.hasCustomName() || e.getCustomName() == null) return false;
        String n = Formatting.strip(e.getCustomName().getString());
        if (n == null) return false;
        n = n.toLowerCase(Locale.ROOT);
        for (String want : names) if (n.contains(want.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ------------------------------------------------------------------ claiming

    private static void startClaim(MinecraftClient client) {
        stopMiner(client);
        stopWalking(client);
        ClientPlayerEntity player = client.player;
        int pigeon = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            String n = Formatting.strip(s.getName().getString());
            if (!s.isEmpty() && n != null && n.toLowerCase(Locale.ROOT).contains("pigeon")) pigeon = i;
        }
        if (pigeon >= 0) {
            slotBeforeClaim = player.getInventory().getSelectedSlot();
            player.getInventory().setSelectedSlot(pigeon);
            state = State.CLAIM_SELECT;
            timer = 4;
            setStatus(client, "Claiming with the Royal Pigeon...");
            return;
        }
        BlockPos npc = locations.get("Claim NPC");
        if (npc != null) {
            if (player.getBlockPos().getSquaredDistance(npc) > 4 * 4) {
                walker = Walker.to(npc, 2.5);
                onArrive = () -> {
                    state = State.CLAIM_NPC;
                    timer = 10;
                };
                state = State.TRAVEL;
                setStatus(client, "Walking to the claim NPC...");
            } else {
                state = State.CLAIM_NPC;
                timer = 10;
            }
            return;
        }
        setStatus(client, "A commission is done. Put a Royal Pigeon in your hotbar, or set \"Claim NPC\" in the menu.");
        if (!warnedNoClaim) {
            warnedNoClaim = true;
            player.sendMessage(Text.literal("[Commissions] ").formatted(Formatting.AQUA).append(Text.literal(
                    "A commission is finished but I can't claim it: put a Royal Pigeon in your hotbar, or stand by the "
                            + "Emissary / King and click \"Set here\" on \"Claim NPC\" in O > Commissions...")
                    .formatted(Formatting.YELLOW)), false);
        }
        waitFor(200);
    }

    private static void claim(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        switch (state) {
            case CLAIM_SELECT -> {                    // give the slot change a moment, then use the pigeon
                if (--timer > 0) return;
                client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                state = State.CLAIM_OPEN;
                timer = 60;
            }
            case CLAIM_NPC -> {                       // right-click the NPC by the claim spot
                if (--timer > 0) return;
                Entity npc = findNpc(world, player);
                if (npc == null) {
                    setStatus(client, "Can't find the claim NPC (Emissary / King) near the Claim NPC spot.");
                    waitFor(200);
                    return;
                }
                AutoMinerClient.aimAt(player, npc.getBoundingBox().getCenter());
                client.interactionManager.interactEntity(player, npc, Hand.MAIN_HAND);
                player.swingHand(Hand.MAIN_HAND);
                state = State.CLAIM_OPEN;
                timer = 60;
            }
            case CLAIM_OPEN -> {                      // wait for the Commissions menu
                if (client.currentScreen instanceof HandledScreen<?> hs && isClaimMenu(hs.getTitle().getString())) {
                    state = State.CLAIM_CLICK;
                    timer = 10;
                } else if (--timer <= 0) {
                    if (client.currentScreen != null) player.closeHandledScreen();
                    restoreSlot(player);
                    if (++claimTries >= 3) {
                        claimTries = 0;
                        setStatus(client, "The Commissions menu didn't open. Check the pigeon / NPC.");
                        waitFor(200);
                    } else {
                        waitFor(20);
                    }
                }
            }
            case CLAIM_CLICK -> {                     // click every finished commission, one every half second
                if (--timer > 0) return;
                timer = 10;
                if (!(client.currentScreen instanceof HandledScreen<?>)) {
                    waitFor(20);
                    return;
                }
                ScreenHandler h = player.currentScreenHandler;
                for (Slot slot : h.slots) {
                    if (slot.inventory == player.getInventory() || !slot.hasStack()) continue;
                    if (isClaimable(slot.getStack())) {
                        client.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, player);
                        return;
                    }
                }
                player.closeHandledScreen();          // nothing left to claim
                restoreSlot(player);
                claimTries = 0;
                setStatus(client, "Claimed! Reading the new commissions...");
                waitFor(30);
            }
            default -> { }
        }
    }

    private static void restoreSlot(ClientPlayerEntity player) {
        if (slotBeforeClaim >= 0) {
            player.getInventory().setSelectedSlot(slotBeforeClaim);   // back to your drill
            slotBeforeClaim = -1;
        }
    }

    private static boolean isClaimable(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            String s = Formatting.strip(line.getString());
            if (s == null) continue;
            s = s.toLowerCase(Locale.ROOT);
            if (s.contains("completed") || s.contains("click to claim") || s.contains("claim reward")
                    || s.contains("ready to claim") || s.equals("done") || s.contains("100%")) return true;
        }
        return false;
    }

    private static boolean isClaimMenu(String title) {
        String t = Formatting.strip(title);
        if (t == null) return false;
        t = t.toLowerCase(Locale.ROOT);
        return t.contains("commission") || t.contains("pigeon") || t.contains("emissary");
    }

    private static Entity findNpc(ClientWorld world, ClientPlayerEntity player) {
        List<String> names = List.of("Emissary", "King", "Commission");
        Entity best = null;
        double bestD = 6 * 6;
        Vec3d tag = null;
        for (Entity e : world.getEntities()) {
            if (e instanceof ArmorStandEntity && nameMatches(e, names) && e.squaredDistanceTo(player) < bestD) {
                tag = new Vec3d(e.getX(), e.getY(), e.getZ());
                bestD = e.squaredDistanceTo(player);
            }
        }
        if (tag == null) return null;
        double best2 = 4.0;
        for (Entity e : world.getEntities()) {                // the NPC standing under the nametag
            if (e == player || e instanceof ArmorStandEntity) continue;
            double dx = tag.x - e.getX(), dz = tag.z - e.getZ();
            double d = dx * dx + dz * dz;
            if (d < best2 && Math.abs(tag.y - e.getY()) < 3.5) {
                best = e;
                best2 = d;
            }
        }
        if (best == null) {                                   // no separate body: click the nametag itself
            for (Entity e : world.getEntities()) {
                if (e instanceof ArmorStandEntity && e.getX() == tag.x && e.getY() == tag.y && e.getZ() == tag.z) best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ tab list

    /** Reads commission lines like "Royal Mines Mithril: 45%" or "Goblin Slayer: DONE" from the tab list. */
    public static List<Commission> readCommissions(MinecraftClient client) {
        List<Commission> out = new ArrayList<>();
        if (client.getNetworkHandler() == null) return out;
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            Text t = entry.getDisplayName();
            if (t == null) continue;
            String s = Formatting.strip(t.getString());
            if (s == null) continue;
            Matcher m = LINE.matcher(s);
            if (!m.matches()) continue;
            String name = m.group(1).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean looks = false;
            for (String w : COMMISSION_WORDS) if (lower.contains(w)) looks = true;
            if (!looks) continue;
            String prog = m.group(2);
            boolean done = prog.equals("DONE");
            if (!done && prog.endsWith("%")) {
                try {
                    done = Double.parseDouble(prog.substring(0, prog.length() - 1)) >= 100.0;   // "100%" counts too
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(new Commission(name, prog, done));
        }
        return out;
    }

    public static List<Commission> currentCommissions() {
        return commissions;
    }

    private static boolean anyDone() {
        if (tick % 20 != 0) return false;
        for (Commission c : commissions) if (c.done()) return true;
        return false;
    }

    private static boolean stillHave(String name) {
        if (commissions.isEmpty()) return true;              // tab list not read yet / flickered
        for (Commission c : commissions) if (c.name().equals(name)) return true;
        return false;
    }

    private static String progressOf(String name) {
        for (Commission c : commissions) if (c.name().equals(name)) return " (" + c.progress() + ")";
        return "";
    }

    // ------------------------------------------------------------------ helpers

    private static void stopWork(MinecraftClient client) {
        triedOres.clear();
        lastOre = null;
        stopMiner(client);
        stopWalking(client);
        onArrive = null;
        task = null;
    }

    private static void waitFor(int ticks) {
        state = State.WAIT;
        timer = ticks;
    }

    private static void setStatus(MinecraftClient client, String s) {
        status = s;
        if (!s.equals(lastShown)) show(client, s);
    }

    private static void show(MinecraftClient client, String s) {
        lastShown = s;
        client.inGameHud.setOverlayMessage(Text.literal("[Commissions] ").formatted(Formatting.AQUA)
                .append(Text.literal(s).formatted(Formatting.WHITE)), false);
    }

    // ------------------------------------------------------------------ saving locations

    public static void setLocation(String area, BlockPos pos) {
        if (pos == null) locations.remove(area);
        else locations.put(area, pos.toImmutable());
        save();
    }

    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BlockPos> e : locations.entrySet()) {
            BlockPos p = e.getValue();
            sb.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append(' ')
                    .append(e.getKey()).append('\n');
        }
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static void load() {
        if (!Files.exists(configFile)) return;
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ", 4);
                if (p.length == 4) {
                    locations.put(p[3], new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
