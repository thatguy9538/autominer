package com.example.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractSkullBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Miner (Fabric, Minecraft 1.21.10)
 *
 * Mines the blocks you pick that are within your reach and in sight, and copes
 * with custom server blocks and drills:
 *  - player-head blocks are told apart by their skin texture
 *  - once it starts on a block it keeps going until the block is gone (air / bedrock)
 *  - "hold click" mode aims at the block and holds left click
 *  - aims at particles the server puts on the block (e.g. "precision mining")
 *  - plans paths of connected target blocks; queued blocks that change are skipped
 *  - each target has a priority (1-5): higher ones are mined first
 *  - a menu (O) to manage targets, priorities and settings; all saved to config/autominer.txt
 *
 *   O = menu   G = add / remove the block you're looking at   J = clear the list
 *   H = turn mining on / off   L = switch mode: hold click / classic
 */
public class AutoMinerClient implements ClientModInitializer {

    public static final String MOD_ID = "autominer";

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    /** Ticks a block may be out of sight before giving up on it. */
    private static final int GRACE_TICKS = 10;
    /** Give up on a block that hasn't broken after this long (30 s), and skip it for a while. */
    private static final int MAX_BLOCK_TICKS = 600;
    private static final int SKIP_TICKS = 200;
    /** How often to look for a higher-priority block than the planned path (1 s). */
    private static final int PRIORITY_CHECK_TICKS = 20;

    private static final Pattern TEXTURE = Pattern.compile("value:\"([^\"]+)\"");

    // ---- settings (shown in the menu, saved to the config file)
    public static boolean holdMode = true;
    /** Fraction of the remaining angle turned per tick. */
    public static float turnFraction = 0.20f;
    /** Ticks to wait after each block. */
    public static int pauseSetting = 5;
    /** Longest path planned at once. */
    public static int maxPath = 12;

    /** A block type to mine. */
    public static class Target {
        public String name;
        public int priority;

        Target(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
    }

    public static final int MIN_PRIORITY = 1, MAX_PRIORITY = 5, DEFAULT_PRIORITY = 3;

    /** Target keys ("minecraft:stone", "minecraft:player_head#1a2b3c") to their name and priority. */
    public static final Map<String, Target> targets = new LinkedHashMap<>();
    /** Blocks we gave up on, and the tick until which to skip them. */
    private static final Map<BlockPos, Long> skipped = new HashMap<>();

    private static KeyBinding selectKey, toggleKey, clearKey, modeKey, menuKey;
    private static Path configFile;

    public static boolean enabled = false;
    private static BlockPos currentPos = null;
    private static Direction currentSide = Direction.UP;
    private static Vec3d currentAim = null;
    private static boolean started = false;   // have we begun hitting the current block?
    private static int lostTicks = 0, blockTicks = 0, pauseTicks = 0;
    private static long tickCount = 0;
    private static boolean holdingAttack = false;

    /** Latest server particle on the block being mined, and when it appeared. */
    private static Vec3d particlePoint = null;
    private static BlockPos particleBlock = null;
    private static long particleTick = 0;
    private static final int PARTICLE_TICKS = 40;

    private record Aim(Direction side, Vec3d point) {}

    /** A block queued in the path, and what it was when we queued it. */
    private record PathEntry(BlockPos pos, String key) {}

    private static final Deque<PathEntry> path = new ArrayDeque<>();
    private static final int EXTEND_AT = 2, EXTEND_RETRY = 5;
    private static int extendCooldown = 0;

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("autominer.txt");
        load();
        menuKey = reg("key.autominer.menu", GLFW.GLFW_KEY_O);
        selectKey = reg("key.autominer.select", GLFW.GLFW_KEY_G);
        toggleKey = reg("key.autominer.toggle", GLFW.GLFW_KEY_H);
        clearKey = reg("key.autominer.clear", GLFW.GLFW_KEY_J);
        modeKey = reg("key.autominer.mode", GLFW.GLFW_KEY_L);
        ClientTickEvents.END_CLIENT_TICK.register(AutoMinerClient::onTick);
    }

    private static KeyBinding reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    // ------------------------------------------------------------------ actions (also used by the menu)

    /** Adds or removes the block under the crosshair. Returns a message to show. */
    public static Text toggleLookedAt(MinecraftClient client) {
        ClientWorld world = client.world;
        HitResult hit = client.crosshairTarget;
        if (world == null || !(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            return Text.literal("Look at a block first.").formatted(Formatting.RED);
        }
        BlockPos pos = bhr.getBlockPos();
        if (world.getBlockState(pos).getHardness(world, pos) < 0) {
            return Text.literal("That block can't be mined.").formatted(Formatting.RED);
        }
        String key = keyOf(world, pos);
        Text name = nameOf(world, pos, key);
        boolean added = !targets.containsKey(key);
        if (added) targets.put(key, new Target(name.getString(), DEFAULT_PRIORITY));
        else targets.remove(key);
        resetCurrent(client);
        save();
        return Text.literal(added ? "Added: " : "Removed: ").formatted(added ? Formatting.GREEN : Formatting.RED)
                .append(name.copy().formatted(Formatting.WHITE))
                .append(Text.literal("  (" + targets.size() + " target" + (targets.size() == 1 ? "" : "s") + ")")
                        .formatted(Formatting.GRAY));
    }

    public static void setEnabled(MinecraftClient client, boolean on) {
        if (on && targets.isEmpty()) return;
        enabled = on;
        resetCurrent(client);
        pauseTicks = 0;
        if (!on) KeyBinding.updatePressedStates();   // give the mouse button back
    }

    public static void setHoldMode(MinecraftClient client, boolean hold) {
        holdMode = hold;
        resetCurrent(client);
        save();
    }

    public static void clearTargets(MinecraftClient client) {
        targets.clear();
        skipped.clear();
        resetCurrent(client);
        enabled = false;
        save();
    }

    /** Call after the menu changes priorities or settings. */
    public static void settingsChanged(MinecraftClient client) {
        path.clear();          // replan with the new priorities
        extendCooldown = 0;
        save();
    }

    // ------------------------------------------------------------------ tick

    private static void onTick(MinecraftClient client) {
        tickCount++;
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            enabled = false;
            currentPos = null;
            holdingAttack = false;
            return;
        }

        handleKeys(client);

        if (!enabled || targets.isEmpty() || client.currentScreen != null || player.isSpectator()) {
            releaseAttack(client);
            return;
        }

        if (pauseTicks > 0) {                       // short break after each block
            pauseTicks--;
            releaseAttack(client);
            return;
        }

        Vec3d eye = player.getEyePos();

        // ---- carry on with the current block, or decide it's done / lost
        if (currentPos != null) {
            BlockState state = world.getBlockState(currentPos);
            blockTicks++;
            if (isGone(state)) {                    // broke it: pause, then the next one
                finishBlock(client);
                pauseTicks = pauseSetting;
                return;
            }
            if (!started && !isTarget(world, currentPos)) {
                dropBlock(client);                  // changed before we even hit it
            } else if (!inReach(player, currentPos)) {
                dropBlock(client);                  // walked out of reach
            } else if (blockTicks > MAX_BLOCK_TICKS) {
                skipped.put(currentPos, tickCount + SKIP_TICKS);   // stuck (ghost block?): skip it for a while
                dropBlock(client);
            } else {
                Aim aim = visibleAim(world, eye, currentPos, player);
                if (aim != null) {
                    lostTicks = 0;
                    currentSide = aim.side();
                    currentAim = aim.point();
                } else if (++lostTicks > GRACE_TICKS) {
                    dropBlock(client);              // out of sight for too long
                }
            }
        }

        // ---- follow the path: skip queued blocks that changed, take the next one, plan ahead
        prunePath(world, player);
        if (extendCooldown > 0) extendCooldown--;
        if (tickCount % PRIORITY_CHECK_TICKS == 0) checkForHigherPriority(world, eye, player);
        if (currentPos == null) {
            takeFromPath(world, eye, player);
            if (currentPos == null && extendCooldown == 0) {
                extendPath(world, eye, player);
                takeFromPath(world, eye, player);
            }
            if (currentPos == null) {               // nothing in reach
                releaseAttack(client);
                return;
            }
        }
        if (path.size() <= EXTEND_AT && extendCooldown == 0) extendPath(world, eye, player);   // nearing the end

        // ---- aim at the server's particle on the block if there is one
        Aim pAim = particleAim(world, eye, player);
        if (pAim != null) {
            currentSide = pAim.side();
            currentAim = pAim.point();
        }

        // ---- hit it
        if (holdMode) {
            aimAt(player, currentAim);
            HitResult hit = client.crosshairTarget;
            boolean onTarget = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(currentPos);
            setAttack(client, onTarget);           // only hold the button when it's really on our block
            if (onTarget) started = true;
        } else {
            if (pAim != null) aimAt(player, currentAim);
            if (client.interactionManager.updateBlockBreakingProgress(currentPos, currentSide)) {
                player.swingHand(Hand.MAIN_HAND);
            }
            started = true;
        }
    }

    private static void handleKeys(MinecraftClient client) {
        while (menuKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new AutoMinerScreen());
        }
        while (selectKey.wasPressed()) say(client, toggleLookedAt(client));
        while (clearKey.wasPressed()) {
            clearTargets(client);
            say(client, Text.literal("Auto Miner: target list cleared").formatted(Formatting.YELLOW));
        }
        while (modeKey.wasPressed()) {
            setHoldMode(client, !holdMode);
            say(client, Text.literal("Auto Miner mode: ").formatted(Formatting.YELLOW)
                    .append(Text.literal(holdMode ? "hold click" : "classic").formatted(Formatting.WHITE)));
        }
        while (toggleKey.wasPressed()) {
            if (targets.isEmpty()) {
                say(client, Text.literal("Add a block first (G or the O menu).").formatted(Formatting.RED));
                continue;
            }
            setEnabled(client, !enabled);
            say(client, Text.literal("Auto Miner: ").formatted(Formatting.YELLOW)
                    .append(enabled ? Text.literal("ON").formatted(Formatting.GREEN)
                            : Text.literal("OFF").formatted(Formatting.RED))
                    .append(Text.literal(" (" + (holdMode ? "hold click" : "classic") + ")").formatted(Formatting.GRAY)));
        }
    }

    // ------------------------------------------------------------------ particles

    /** Called for every particle the server sends. Remembers ones sitting on the surface of our block. */
    public static void onServerParticle(ParticleEffect effect, double x, double y, double z) {
        if (!enabled || currentPos == null) return;
        if (effect instanceof BlockStateParticleEffect || effect instanceof ItemStackParticleEffect) return;
        Vec3d c = Vec3d.ofCenter(currentPos);
        double out = Math.max(Math.abs(x - c.x), Math.max(Math.abs(y - c.y), Math.abs(z - c.z)));
        if (out > 0.8 || out < 0.35) return;
        particlePoint = new Vec3d(x, y, z);
        particleBlock = currentPos;
        particleTick = tickCount;
    }

    private static Aim particleAim(ClientWorld world, Vec3d eye, ClientPlayerEntity player) {
        if (particlePoint == null || !currentPos.equals(particleBlock) || tickCount - particleTick > PARTICLE_TICKS) {
            return null;
        }
        Vec3d dir = particlePoint.subtract(eye);
        double dist = dir.length();
        if (dist < 0.01) return null;
        Vec3d end = eye.add(dir.multiply((dist + 0.6) / dist));
        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(currentPos)) {
            return new Aim(hit.getSide(), hit.getPos());
        }
        return null;
    }

    // ------------------------------------------------------------------ block bookkeeping

    private static void finishBlock(MinecraftClient client) {
        particlePoint = null;
        releaseAttack(client);
        currentPos = null;
        started = false;
    }

    private static void dropBlock(MinecraftClient client) {
        particlePoint = null;
        if (!holdMode && started && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        releaseAttack(client);
        currentPos = null;
        started = false;
    }

    private static void resetCurrent(MinecraftClient client) {
        if (currentPos != null) dropBlock(client);
        path.clear();
        extendCooldown = 0;
        lostTicks = 0;
        blockTicks = 0;
    }

    private static boolean isGone(BlockState state) {
        return state.isAir() || state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.BARRIER);
    }

    // ------------------------------------------------------------------ paths and priorities

    /** Drop queued blocks that changed, or that are out of reach now (e.g. you moved). */
    private static void prunePath(ClientWorld world, ClientPlayerEntity player) {
        path.removeIf(e -> !inReach(player, e.pos()) || !keyOf(world, e.pos()).equals(e.key()));
    }

    /** Safety margin (blocks) kept inside your reach. */
    private static final double REACH_MARGIN = 0.3;

    /**
     * Strict reach check: the block's centre must be within your reach minus a small margin
     * (servers usually measure to the centre), and the game must agree you can reach it.
     */
    private static boolean inReach(ClientPlayerEntity player, BlockPos pos) {
        double range = player.getBlockInteractionRange() - REACH_MARGIN;
        return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range
                && player.canInteractWithBlockAt(pos, 0.0);
    }

    private static void takeFromPath(ClientWorld world, Vec3d eye, ClientPlayerEntity player) {
        while (!path.isEmpty()) {
            PathEntry e = path.pollFirst();
            if (!keyOf(world, e.pos()).equals(e.key()) || !isTarget(world, e.pos())) continue;   // changed: skip
            if (skipped.containsKey(e.pos()) || !inReach(player, e.pos())) continue;
            Aim aim = visibleAim(world, eye, e.pos(), player);
            if (aim == null) continue;
            currentPos = e.pos();
            currentSide = aim.side();
            currentAim = aim.point();
            started = false;
            lostTicks = 0;
            blockTicks = 0;
            return;
        }
    }

    /** Adds blocks to the end of the path, best first (priority, then touching the last one, then distance). */
    private static void extendPath(ClientWorld world, Vec3d eye, ClientPlayerEntity player) {
        skipped.values().removeIf(until -> until < tickCount);
        int before = path.size();
        while (path.size() < maxPath) {
            BlockPos tail = !path.isEmpty() ? path.peekLast().pos() : currentPos;
            PathEntry next = bestTarget(world, eye, player, tail);
            if (next == null) break;
            path.addLast(next);
        }
        if (path.size() == before) extendCooldown = EXTEND_RETRY;
    }

    /** If a block with higher priority than everything planned is in reach, replan to go there next. */
    private static void checkForHigherPriority(ClientWorld world, Vec3d eye, ClientPlayerEntity player) {
        int planned = 0;
        for (PathEntry e : path) planned = Math.max(planned, priorityOfKey(e.key()));
        if (path.isEmpty()) return;
        PathEntry best = bestTarget(world, eye, player, null);
        if (best != null && priorityOfKey(best.key()) > planned) {
            path.clear();
            path.addLast(best);
            extendCooldown = 0;
        }
    }

    /**
     * The best block to mine next: highest priority first; among equal priority, one touching
     * `tail` (so it follows veins), then the closest to `tail` (or to you).
     */
    private static PathEntry bestTarget(ClientWorld world, Vec3d eye, ClientPlayerEntity player, BlockPos tail) {
        int r = (int) Math.ceil(player.getBlockInteractionRange()) + 1;
        BlockPos center = BlockPos.ofFloored(eye);
        Vec3d from = tail != null ? Vec3d.ofCenter(tail) : eye;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        PathEntry best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (pos.equals(currentPos) || queued(pos) || skipped.containsKey(pos)) continue;
                    if (!isTarget(world, pos)) continue;
                    String key = keyOf(world, pos);
                    Vec3d c = Vec3d.ofCenter(pos);
                    boolean touching = tail != null && Math.abs(pos.getX() - tail.getX()) <= 1
                            && Math.abs(pos.getY() - tail.getY()) <= 1 && Math.abs(pos.getZ() - tail.getZ()) <= 1;
                    double score = (MAX_PRIORITY - priorityOfKey(key)) * 1_000_000.0
                            + (touching ? 0 : 10_000.0)
                            + c.squaredDistanceTo(from) * 10 + c.squaredDistanceTo(eye);
                    if (score >= bestScore) continue;
                    BlockPos candidate = pos.toImmutable();
                    if (!inReach(player, candidate)) continue;
                    if (visibleAim(world, eye, candidate, player) == null) continue;
                    bestScore = score;
                    best = new PathEntry(candidate, key);
                }
            }
        }
        return best;
    }

    private static boolean queued(BlockPos pos) {
        for (PathEntry e : path) if (e.pos().equals(pos)) return true;
        return false;
    }

    // ------------------------------------------------------------------ matching

    private static boolean isTarget(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        if (!(state.getBlock() instanceof AbstractSkullBlock)) {
            return targets.containsKey(Registries.BLOCK.getId(state.getBlock()).toString());
        }
        return targets.containsKey(keyOf(world, pos));
    }

    private static int priorityOfKey(String key) {
        Target t = targets.get(key);
        return t == null ? 0 : t.priority;
    }

    /** "minecraft:stone", or for heads "minecraft:player_head#<texture hash>". */
    static String keyOf(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        if (state.getBlock() instanceof AbstractSkullBlock) {
            String tex = headTexture(world, pos);
            if (tex != null) id += "#" + Integer.toHexString(tex.hashCode());
        }
        return id;
    }

    private static String headTexture(ClientWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return null;
        try {
            NbtCompound nbt = be.createNbt(world.getRegistryManager());
            NbtElement profile = nbt.get("profile");
            if (profile == null) return null;
            String s = profile.toString();
            Matcher m = TEXTURE.matcher(s);
            return m.find() ? m.group(1) : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static Text nameOf(ClientWorld world, BlockPos pos, String key) {
        MutableText name = world.getBlockState(pos).getBlock().getName().copy();
        int hash = key.indexOf('#');
        if (hash >= 0) name.append(Text.literal(" [skin " + key.substring(hash + 1, Math.min(key.length(), hash + 7)) + "]"));
        return name;
    }

    // ------------------------------------------------------------------ seeing and aiming

    private static Aim visibleAim(ClientWorld world, Vec3d eye, BlockPos pos, ClientPlayerEntity player) {
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(world, pos);
        Box b = shape.isEmpty() ? new Box(pos) : shape.getBoundingBox().offset(pos);

        Aim a = tryPoint(world, eye, pos, player, b.getCenter());
        if (a != null) return a;

        double[] f = {0.2, 0.5, 0.8};
        double in = 0.02;
        for (Direction dir : Direction.values()) {
            for (double u : f) {
                for (double v : f) {
                    double x, y, z;
                    switch (dir.getAxis()) {
                        case X -> {
                            x = dir == Direction.EAST ? b.maxX - in : b.minX + in;
                            y = MathHelper.lerp(u, b.minY, b.maxY);
                            z = MathHelper.lerp(v, b.minZ, b.maxZ);
                        }
                        case Y -> {
                            y = dir == Direction.UP ? b.maxY - in : b.minY + in;
                            x = MathHelper.lerp(u, b.minX, b.maxX);
                            z = MathHelper.lerp(v, b.minZ, b.maxZ);
                        }
                        default -> {
                            z = dir == Direction.SOUTH ? b.maxZ - in : b.minZ + in;
                            x = MathHelper.lerp(u, b.minX, b.maxX);
                            y = MathHelper.lerp(v, b.minY, b.maxY);
                        }
                    }
                    a = tryPoint(world, eye, pos, player, new Vec3d(x, y, z));
                    if (a != null) return a;
                }
            }
        }
        return null;
    }

    private static Aim tryPoint(ClientWorld world, Vec3d eye, BlockPos pos, ClientPlayerEntity player, Vec3d p) {
        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, p, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
            return new Aim(hit.getSide(), hit.getPos());
        }
        return null;
    }

    private static final float MAX_TURN = 25f, MIN_TURN = 0.4f;

    /** Turns toward the point smoothly: fast when it's far away, slowing down as it gets close. */
    private static void aimAt(ClientPlayerEntity player, Vec3d point) {
        if (point == null) return;
        Vec3d eye = player.getEyePos();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, h)), -90f, 90f);
        float yaw = player.getYaw() + ease(MathHelper.wrapDegrees(targetYaw - player.getYaw()));
        float pitch = MathHelper.clamp(player.getPitch() + ease(targetPitch - player.getPitch()), -90f, 90f);
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
    }

    private static float ease(float diff) {
        float abs = Math.abs(diff);
        if (abs <= MIN_TURN) return diff;
        float step = MathHelper.clamp(abs * turnFraction, MIN_TURN, MAX_TURN);
        return Math.copySign(step, diff);
    }

    // ------------------------------------------------------------------ the attack button

    private static void setAttack(MinecraftClient client, boolean down) {
        client.options.attackKey.setPressed(down);
        holdingAttack = down;
    }

    private static void releaseAttack(MinecraftClient client) {
        if (holdingAttack) {
            client.options.attackKey.setPressed(false);
            holdingAttack = false;
        }
    }

    static void say(MinecraftClient client, Text msg) {
        client.inGameHud.setOverlayMessage(msg, false);
    }

    // ------------------------------------------------------------------ saving

    /** Targets sorted highest priority first (for the menu). */
    public static List<Map.Entry<String, Target>> sortedTargets() {
        List<Map.Entry<String, Target>> list = new ArrayList<>(targets.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue().priority, a.getValue().priority));
        return list;
    }

    public static void save() {
        if (configFile == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("mode ").append(holdMode ? "hold" : "classic").append('\n');
        sb.append("turn ").append(turnFraction).append('\n');
        sb.append("pause ").append(pauseSetting).append('\n');
        sb.append("path ").append(maxPath).append('\n');
        for (Map.Entry<String, Target> e : targets.entrySet()) {
            sb.append("target ").append(e.getValue().priority).append(' ').append(e.getKey()).append(' ')
                    .append(URLEncoder.encode(e.getValue().name, StandardCharsets.UTF_8)).append('\n');
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
                String[] p = line.trim().split(" ");
                switch (p[0]) {
                    case "mode" -> holdMode = p.length < 2 || !p[1].equals("classic");
                    case "turn" -> turnFraction = MathHelper.clamp(Float.parseFloat(p[1]), 0.05f, 0.6f);
                    case "pause" -> pauseSetting = MathHelper.clamp(Integer.parseInt(p[1]), 0, 40);
                    case "path" -> maxPath = MathHelper.clamp(Integer.parseInt(p[1]), 1, 40);
                    case "target" -> {
                        if (p.length >= 4) {
                            int prio = MathHelper.clamp(Integer.parseInt(p[1]), MIN_PRIORITY, MAX_PRIORITY);
                            targets.put(p[2], new Target(URLDecoder.decode(p[3], StandardCharsets.UTF_8), prio));
                        }
                    }
                    default -> { }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
