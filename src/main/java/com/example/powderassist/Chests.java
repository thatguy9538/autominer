package com.example.powderassist;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the treasure (powder) chests that appear while you mine, and solves their lockpick:
 * the server puts crit particles on the spot you have to hit, so it aims there and clicks.
 */
public final class Chests {

    private Chests() {
    }

    /** Treasure chests -> tick they were found. */
    public static final Map<BlockPos, Long> treasure = new LinkedHashMap<>();
    /** Every chest we've seen nearby (so a new one can be told apart from ones that were always there). */
    private static final Set<BlockPos> known = new HashSet<>();
    private static final int SCAN_XZ = 10, SCAN_Y = 6;
    private static final long KEEP_TICKS = 20 * 120;      // forget a chest after 2 minutes
    private static BlockPos lastScanCenter = null;
    private static long tick = 0;
    /** Ticks left in which a newly seen chest counts as treasure (after the "uncovered" chat). */
    private static int uncoverWindow = 0;

    // solver
    private static Vec3d point = null;
    private static BlockPos pointChest = null;
    private static long pointTick = -1000;
    private static Vec3d clickedPoint = null;
    private static long clickedTick = -1000;
    private static int clickCooldown = 0;
    /** True while the solver is busy with a chest (auto-mine waits). */
    public static boolean solving = false;
    /** The chest the solver is working on, for the HUD. */
    public static BlockPos solvingChest = null;

    // ------------------------------------------------------------------ events

    public static void onChat(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("uncovered a treasure chest") || l.contains("found a treasure chest")) {
            uncoverWindow = 40;
            Tracker.chestsFound++;
        }
        if (l.contains("chest lockpicked") || l.contains("lockpicked the chest")) {
            Tracker.chestsOpened++;
            if (solvingChest != null) treasure.remove(solvingChest);
            point = null;
        }
    }

    /** Server particle: crit particles sitting on a treasure chest mark where to hit it. */
    public static void onParticle(ParticleEffect effect, double x, double y, double z) {
        if (effect.getType() != ParticleTypes.CRIT) return;
        for (BlockPos c : treasure.keySet()) {
            Vec3d m = Vec3d.ofCenter(c);
            double out = Math.max(Math.abs(x - m.x), Math.max(Math.abs(y - m.y), Math.abs(z - m.z)));
            if (out < 0.9) {
                point = new Vec3d(x, y, z);
                pointChest = c;
                pointTick = tick;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient client) {
        tick++;
        if (uncoverWindow > 0) uncoverWindow--;
        if (clickCooldown > 0) clickCooldown--;
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;

        if (tick % 5 == 0) scan(world, player);
        treasure.entrySet().removeIf(e -> !isChest(world.getBlockState(e.getKey())) || tick - e.getValue() > KEEP_TICKS);

        solve(client, world, player);
    }

    private static void scan(ClientWorld world, ClientPlayerEntity player) {
        BlockPos me = player.getBlockPos();
        BlockPos.Mutable p = new BlockPos.Mutable();
        Set<BlockPos> now = new HashSet<>();
        for (int x = -SCAN_XZ; x <= SCAN_XZ; x++) {
            for (int y = -SCAN_Y; y <= SCAN_Y; y++) {
                for (int z = -SCAN_XZ; z <= SCAN_XZ; z++) {
                    p.set(me.getX() + x, me.getY() + y, me.getZ() + z);
                    if (isChest(world.getBlockState(p))) now.add(p.toImmutable());
                }
            }
        }
        for (BlockPos c : now) {
            if (known.contains(c) || treasure.containsKey(c)) continue;
            // a chest is "new" if we already looked at that spot last time and it wasn't there,
            // or if chat just said one was uncovered
            boolean wasScanned = lastScanCenter != null
                    && Math.abs(c.getX() - lastScanCenter.getX()) <= SCAN_XZ - 1
                    && Math.abs(c.getY() - lastScanCenter.getY()) <= SCAN_Y - 1
                    && Math.abs(c.getZ() - lastScanCenter.getZ()) <= SCAN_XZ - 1;
            boolean close = c.getSquaredDistance(me) <= 7 * 7;
            if (close && (wasScanned || uncoverWindow > 0)) {
                treasure.put(c, tick);
                if (uncoverWindow == 0) Tracker.chestsFound++;   // no chat line for it: count it here
                uncoverWindow = 0;
            } else {
                known.add(c);
            }
        }
        known.removeIf(c -> c.getSquaredDistance(me) < (SCAN_XZ - 1) * (SCAN_XZ - 1) && !now.contains(c));
        if (known.size() > 4000) known.clear();
        lastScanCenter = me;
    }

    private static boolean isChest(BlockState s) {
        return s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST);
    }

    // ------------------------------------------------------------------ solver

    private static void solve(MinecraftClient client, ClientWorld world, ClientPlayerEntity player) {
        solving = false;
        solvingChest = null;
        if (!PowderAssistClient.solver || client.currentScreen != null) return;
        if (point == null || pointChest == null || !treasure.containsKey(pointChest)) return;
        if (tick - pointTick > 30) return;                 // particles stopped: solved or gone
        double reach = player.getBlockInteractionRange();
        if (player.getEyePos().distanceTo(point) > reach) return;

        solving = true;
        solvingChest = pointChest;
        float off = Aim.turnToward(player, point, 0.6f, 45f);
        if (!PowderAssistClient.solverAutoClick || clickCooldown > 0 || off > 1.5f) return;

        // don't click the same spot twice in a row unless it's still showing a while later
        if (clickedPoint != null && clickedPoint.squaredDistanceTo(point) < 0.01 && tick - clickedTick < 10) return;

        Vec3d eye = player.getEyePos();
        Vec3d dir = point.subtract(eye);
        Vec3d end = eye.add(dir.normalize().multiply(dir.length() + 0.5));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pointChest)) return;

        if (PowderAssistClient.solverRightClick) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        } else {
            client.interactionManager.attackBlock(hit.getBlockPos(), hit.getSide());
        }
        player.swingHand(Hand.MAIN_HAND);
        clickedPoint = point;
        clickedTick = tick;
        clickCooldown = 4;
    }

    /** Where the solver wants you to hit (for the HUD marker), or null. */
    public static Vec3d solverPoint() {
        return solving ? point : null;
    }

    public static void clear() {
        treasure.clear();
        known.clear();
        point = null;
        lastScanCenter = null;
    }
}
