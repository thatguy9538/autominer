package com.example.powderassist;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Hardstone auto-mine: picks the block in reach that needs the least turning, looks at it
 * smoothly and holds left click until it breaks. Waits while the chest solver is busy.
 */
public final class Miner {

    private Miner() {
    }

    private static BlockPos current = null;
    private static Vec3d aimPoint = null;
    private static int blockTicks = 0, lostTicks = 0;
    private static boolean holding = false;
    private static long tick = 0;
    /** Blocks that wouldn't break (or couldn't be seen any more), skipped for a while. */
    private static final Map<BlockPos, Long> skip = new HashMap<>();
    /** Give up on one block after this many ticks of holding click on it. */
    private static final int MAX_BLOCK_TICKS = 160;
    private static final double REACH_MARGIN = 0.3;

    public static BlockPos current() {
        return current;
    }

    public static void tick(MinecraftClient client) {
        tick++;
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (!PowderAssistClient.autoMine) return;
        if (client.currentScreen != null || player.isDead()) {
            release(client);
            return;
        }
        if (Chests.solving) {                              // let the chest solver have the camera
            release(client);
            current = null;
            return;
        }
        if (tick % 40 == 0) skip.values().removeIf(t -> t < tick);

        // keep the current block while it's still there, in reach and visible
        if (current != null) {
            BlockState s = world.getBlockState(current);
            if (!wanted(s) || !inReach(player, current)) {
                current = null;
            } else if (++blockTicks > MAX_BLOCK_TICKS) {
                skip.put(current, tick + 600);
                current = null;
            }
        }
        if (current == null) {
            pick(world, player);
            blockTicks = 0;
            lostTicks = 0;
            if (current == null) {
                release(client);
                return;
            }
        }

        // refresh the aim point now and then (the view may change as blocks around it break)
        if (tick % 4 == 0 || aimPoint == null) {
            Vec3d p = visiblePoint(world, player, current);
            if (p == null) {
                if (++lostTicks > 10) {
                    skip.put(current, tick + 200);
                    current = null;
                    release(client);
                    return;
                }
            } else {
                aimPoint = p;
                lostTicks = 0;
            }
        }

        float fraction = 0.08f + PowderAssistClient.turnSpeed * 0.045f;      // 0.125 .. 0.53
        float maxStep = 6f + PowderAssistClient.turnSpeed * 4f;              // 10 .. 46 degrees per tick
        Aim.turnToward(player, aimPoint, fraction, maxStep);

        HitResult hit = client.crosshairTarget;
        boolean onTarget = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                && b.getBlockPos().equals(current);
        client.options.attackKey.setPressed(onTarget);   // only hold click when it's really on the block
        holding = onTarget;
    }

    /** The wanted block in reach that's cheapest to turn to (angle first, distance a little). */
    private static void pick(ClientWorld world, ClientPlayerEntity player) {
        double reach = player.getBlockInteractionRange() - REACH_MARGIN;
        int r = (int) Math.ceil(reach);
        BlockPos eye = BlockPos.ofFloored(player.getEyePos());
        BlockPos.Mutable p = new BlockPos.Mutable();
        BlockPos best = null;
        Vec3d bestPoint = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    p.set(eye.getX() + x, eye.getY() + y, eye.getZ() + z);
                    if (skip.containsKey(p)) continue;
                    if (!wanted(world.getBlockState(p))) continue;
                    if (!inReach(player, p)) continue;
                    if (underFeet(player, p)) continue;       // don't dig out the floor you stand on
                    if (nextToTreasure(p)) continue;
                    BlockPos imm = p.toImmutable();
                    Vec3d v = visiblePoint(world, player, imm);
                    if (v == null) continue;
                    double score = Aim.angleTo(player, v) + 4.0 * player.getEyePos().distanceTo(v);
                    if (score < bestScore) {
                        bestScore = score;
                        best = imm;
                        bestPoint = v;
                    }
                }
            }
        }
        current = best;
        aimPoint = bestPoint;
    }

    /** A point on the block you can see from your eyes: the middle of an open face, or null. */
    private static Vec3d visiblePoint(ClientWorld world, ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d c = Vec3d.ofCenter(pos);
        Vec3d best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (!world.getBlockState(n).getCollisionShape(world, n).isEmpty()) continue;   // face is covered
            Vec3d face = c.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
            if (face.subtract(eye).dotProduct(Vec3d.of(d.getVector())) >= 0) continue;  // facing away from us
            BlockHitResult hit = world.raycast(new RaycastContext(eye, face, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                double dist = eye.squaredDistanceTo(face);
                if (dist < bestD) {
                    bestD = dist;
                    best = face;
                }
            }
        }
        return best;
    }

    private static boolean inReach(ClientPlayerEntity player, BlockPos pos) {
        double range = player.getBlockInteractionRange() - REACH_MARGIN;
        return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    private static boolean underFeet(ClientPlayerEntity player, BlockPos pos) {
        if (pos.getY() >= Math.floor(player.getY() + 0.01)) return false;
        Box foot = player.getBoundingBox().expand(0.15, 0, 0.15);
        return pos.getX() + 1 > foot.minX && pos.getX() < foot.maxX && pos.getZ() + 1 > foot.minZ && pos.getZ() < foot.maxZ;
    }

    private static boolean nextToTreasure(BlockPos p) {
        for (BlockPos c : Chests.treasure.keySet()) if (c.getManhattanDistance(p) <= 0) return true;
        return false;
    }

    private static boolean wanted(BlockState s) {
        if (s.isAir()) return false;
        if (PowderAssistClient.mineHardstone && (s.isOf(Blocks.STONE) || s.isOf(Blocks.INFESTED_STONE))) return true;
        String id = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (PowderAssistClient.mineGemstones && (id.endsWith("stained_glass") || id.endsWith("stained_glass_pane"))) {
            return true;
        }
        if (PowderAssistClient.mineOres && id.endsWith("_ore")) return true;
        return false;
    }

    private static void release(MinecraftClient client) {
        if (holding) {
            client.options.attackKey.setPressed(false);
            holding = false;
        }
    }

    public static void stop(MinecraftClient client) {
        release(client);
        net.minecraft.client.option.KeyBinding.updatePressedStates();
        current = null;
        aimPoint = null;
        skip.clear();
    }
}
