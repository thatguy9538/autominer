package com.example.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Miner 2.0 (Fabric, Minecraft 1.21.10)
 *
 * Mines the blocks you pick that are within your reach and in sight, and copes
 * with custom server blocks and drills:
 *  - player-head blocks are told apart by their skin texture
 *  - add several blocks (e.g. every stage of an ore) and they all count as targets
 *  - once it starts on a block it keeps going until the block is gone (air /
 *    bedrock), even if it changes into another block on the way
 *  - "hold click" mode aims at the block and holds left click, so the server
 *    and your drill decide how fast it breaks
 *  - short pause after each block
 *  - aims at particles the server puts on the block (e.g. "precision mining"
 *    targets), which some servers reward with faster mining
 *
 *   G = add / remove the block you're looking at
 *   J = clear the list
 *   H = turn mining on / off
 *   L = switch mode: hold click (default) / classic
 */
public class AutoMinerClient implements ClientModInitializer {

    public static final String MOD_ID = "autominer";

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    /** Ticks a block may be out of sight before giving up on it. */
    private static final int GRACE_TICKS = 10;
    /** Ticks to wait after a block breaks before starting the next one. */
    private static final int PAUSE_TICKS = 5;
    /** Give up on a block that hasn't broken after this long (30 s), and skip it for a while. */
    private static final int MAX_BLOCK_TICKS = 600;
    private static final int SKIP_TICKS = 200;

    private static final Pattern TEXTURE = Pattern.compile("value:\"([^\"]+)\"");

    private static KeyBinding selectKey, toggleKey, clearKey, modeKey;

    /** Target keys ("minecraft:stone", "minecraft:player_head#1a2b3c") and their display names. */
    private static final Map<String, Text> targets = new LinkedHashMap<>();
    /** Blocks we gave up on, and the tick until which to skip them. */
    private static final Map<BlockPos, Long> skipped = new HashMap<>();

    private static boolean enabled = false;
    private static boolean holdMode = true;
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
    /** How long to keep aiming at a particle after it appeared (2 s). */
    private static final int PARTICLE_TICKS = 40;

    private record Aim(Direction side, Vec3d point) {}

    @Override
    public void onInitializeClient() {
        selectKey = reg("key.autominer.select", GLFW.GLFW_KEY_G);
        toggleKey = reg("key.autominer.toggle", GLFW.GLFW_KEY_H);
        clearKey = reg("key.autominer.clear", GLFW.GLFW_KEY_J);
        modeKey = reg("key.autominer.mode", GLFW.GLFW_KEY_L);
        ClientTickEvents.END_CLIENT_TICK.register(AutoMinerClient::onTick);
    }

    private static KeyBinding reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, key, CATEGORY));
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

        handleKeys(client, world);

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
                pauseTicks = PAUSE_TICKS;
                return;
            }
            if (!started && !isTarget(world, currentPos)) {
                dropBlock(client);                  // changed before we even hit it
            } else if (!player.canInteractWithBlockAt(currentPos, 0.0)) {
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
                // otherwise: briefly hidden, keep going with the last aim
            }
        }

        // ---- pick the closest target if we have nothing
        if (currentPos == null) {
            pickClosest(world, eye, player);
            if (currentPos == null) {               // nothing in reach
                releaseAttack(client);
                return;
            }
        }

        // ---- aim at the server's particle on the block if there is one (faster mining on some servers)
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
            // only hold the button when the crosshair is really on our block, so nothing else gets mined
            setAttack(client, onTarget);
            if (onTarget) started = true;
        } else {
            if (pAim != null) aimAt(player, currentAim);   // the server checks where you're looking
            if (client.interactionManager.updateBlockBreakingProgress(currentPos, currentSide)) {
                player.swingHand(Hand.MAIN_HAND);
            }
            started = true;
        }
    }

    /** Called for every particle the server sends. Remembers ones sitting on the surface of our block. */
    public static void onServerParticle(ParticleEffect effect, double x, double y, double z) {
        if (!enabled || currentPos == null) return;
        if (effect instanceof BlockStateParticleEffect || effect instanceof ItemStackParticleEffect) return;   // break debris
        Vec3d c = Vec3d.ofCenter(currentPos);
        double dx = Math.abs(x - c.x), dy = Math.abs(y - c.y), dz = Math.abs(z - c.z);
        double out = Math.max(dx, Math.max(dy, dz));
        if (out > 0.8 || out < 0.35) return;      // must be on (or just off) one of the block's faces
        particlePoint = new Vec3d(x, y, z);
        particleBlock = currentPos;
        particleTick = tickCount;
    }

    /** Where to aim to look straight at the current particle on our block, or null. */
    private static Aim particleAim(ClientWorld world, Vec3d eye, ClientPlayerEntity player) {
        if (particlePoint == null || !currentPos.equals(particleBlock) || tickCount - particleTick > PARTICLE_TICKS) {
            return null;
        }
        Vec3d dir = particlePoint.subtract(eye);
        double dist = dir.length();
        if (dist < 0.01) return null;
        Vec3d end = eye.add(dir.multiply((dist + 0.6) / dist));   // a bit past it, into the block
        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(currentPos)) {
            return new Aim(hit.getSide(), hit.getPos());
        }
        return null;
    }

    private static void handleKeys(MinecraftClient client, ClientWorld world) {
        while (selectKey.wasPressed()) {
            HitResult hit = client.crosshairTarget;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = bhr.getBlockPos();
                BlockState state = world.getBlockState(pos);
                if (state.getHardness(world, pos) < 0) {
                    say(client, Text.literal("That block can't be mined.").formatted(Formatting.RED));
                    continue;
                }
                String key = keyOf(world, pos);
                boolean added;
                if (targets.containsKey(key)) {
                    targets.remove(key);
                    added = false;
                } else {
                    targets.put(key, nameOf(world, pos, key));
                    added = true;
                }
                resetCurrent(client);
                say(client, Text.literal(added ? "Added: " : "Removed: ")
                        .formatted(added ? Formatting.GREEN : Formatting.RED)
                        .append(nameOf(world, pos, key).copy().formatted(Formatting.WHITE))
                        .append(Text.literal("  (" + targets.size() + " target" + (targets.size() == 1 ? "" : "s") + ")")
                                .formatted(Formatting.GRAY)));
            } else {
                say(client, Text.literal("Look at a block to select it.").formatted(Formatting.RED));
            }
        }
        while (clearKey.wasPressed()) {
            targets.clear();
            skipped.clear();
            resetCurrent(client);
            enabled = false;
            say(client, Text.literal("Auto Miner: target list cleared").formatted(Formatting.YELLOW));
        }
        while (modeKey.wasPressed()) {
            holdMode = !holdMode;
            resetCurrent(client);
            say(client, Text.literal("Auto Miner mode: ").formatted(Formatting.YELLOW)
                    .append(Text.literal(holdMode ? "hold click (for drills / server blocks)" : "classic")
                            .formatted(Formatting.WHITE)));
        }
        while (toggleKey.wasPressed()) {
            if (targets.isEmpty()) {
                say(client, Text.literal("Add a block first (G).").formatted(Formatting.RED));
                continue;
            }
            enabled = !enabled;
            resetCurrent(client);
            pauseTicks = 0;
            if (!enabled) KeyBinding.updatePressedStates();   // give the mouse button back
            say(client, Text.literal("Auto Miner: ").formatted(Formatting.YELLOW)
                    .append(enabled ? Text.literal("ON").formatted(Formatting.GREEN)
                            : Text.literal("OFF").formatted(Formatting.RED))
                    .append(Text.literal(" (" + (holdMode ? "hold click" : "classic") + ")").formatted(Formatting.GRAY)));
        }
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
        lostTicks = 0;
        blockTicks = 0;
    }

    /** Air, bedrock or barrier: the block is broken (or regenerating). */
    private static boolean isGone(BlockState state) {
        return state.isAir() || state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.BARRIER);
    }

    private static void pickClosest(ClientWorld world, Vec3d eye, ClientPlayerEntity player) {
        skipped.values().removeIf(until -> until < tickCount);
        int r = (int) Math.ceil(player.getBlockInteractionRange()) + 1;
        BlockPos center = BlockPos.ofFloored(eye);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos best = null;
        Aim bestAim = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (d >= bestDist) continue;
                    if (!isTarget(world, pos) || skipped.containsKey(pos)) continue;
                    BlockPos candidate = pos.toImmutable();
                    if (!player.canInteractWithBlockAt(candidate, 0.0)) continue;
                    Aim aim = visibleAim(world, eye, candidate, player);
                    if (aim != null) {
                        bestDist = d;
                        best = candidate;
                        bestAim = aim;
                    }
                }
            }
        }
        if (best != null) {
            currentPos = best;
            currentSide = bestAim.side();
            currentAim = bestAim.point();
            started = false;
            lostTicks = 0;
            blockTicks = 0;
        }
    }

    // ------------------------------------------------------------------ matching

    private static boolean isTarget(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        String plain = Registries.BLOCK.getId(state.getBlock()).toString();
        if (!(state.getBlock() instanceof AbstractSkullBlock)) return targets.containsKey(plain);
        return targets.containsKey(keyOf(world, pos));
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

    /** The skin texture of a head block (or its whole profile if no texture is found). */
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

    /**
     * A point on the block you can hit with a clear line of sight, and which face it's on.
     * Tries the middle and a 3x3 grid on every face of the block's real shape, so odd shapes
     * (heads, slabs, panes) and blocks round corners are found too.
     */
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

    /** Fraction of the remaining angle turned each tick: big turns go fast, small ones slow down. */
    private static final float TURN_FRACTION = 0.35f;
    /** Fastest turn per tick, and the smallest step before it just lands on the target (degrees). */
    private static final float MAX_TURN = 45f, MIN_TURN = 0.6f;

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

    /** How far to turn this tick for an angle still to go. */
    private static float ease(float diff) {
        float abs = Math.abs(diff);
        if (abs <= MIN_TURN) return diff;                       // close enough: land on it
        float step = MathHelper.clamp(abs * TURN_FRACTION, MIN_TURN, MAX_TURN);
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

    private static void say(MinecraftClient client, Text msg) {
        client.inGameHud.setOverlayMessage(msg, false);
    }
}
