package com.example.ghostbot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

import java.util.List;
import java.util.function.Predicate;

/** Walks the player along a path found by {@link PathSearch}, re-planning when stuck. */
public class Walker implements Mover {

    public enum Result { WALKING, ARRIVED, FAILED }

    private static final int MAX_NODES = 25_000;
    /** Re-plans in a row that didn't get us any closer before giving up. */
    private static final int MAX_NO_PROGRESS = 6;
    /** How many nodes ahead it may steer straight toward when the way is clear. */
    private static final int LOOKAHEAD = 8;

    private final Predicate<BlockPos> goal;
    private final BlockPos toward;
    private List<PathSearch.Pos> path;
    private boolean partial = false;
    private int index = 0;
    private int noProgress = 0;
    private double bestDist = Double.MAX_VALUE;
    private int stuckTicks = 0, unstuck = 0, ticks = 0;
    private boolean strafeLeft = false;
    private double lastX, lastZ, lastY;
    private boolean holdingKeys = false;

    public Walker(Predicate<BlockPos> goal, BlockPos toward) {
        this.goal = goal;
        this.toward = toward;
    }

    /** A walker to a spot within `radius` blocks of `target`. */
    public static Walker to(BlockPos target, double radius) {
        double r2 = radius * radius;
        return new Walker(p -> p.getSquaredDistance(target) <= r2, target);
    }

    @Override
    public Result tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) return Result.FAILED;
        ticks++;
        PathSearch.World view = worldView(world);
        GameOptions o = client.options;

        BlockPos feet = feet(player);
        if (goal.test(feet) && player.isOnGround()) {
            stop(client);
            return Result.ARRIVED;
        }

        // stuck: wiggle sideways and jump for a moment, then plan again from wherever we ended up
        if (unstuck > 0) {
            unstuck--;
            o.forwardKey.setPressed(unstuck > 5);
            o.backKey.setPressed(unstuck <= 5);
            (strafeLeft ? o.leftKey : o.rightKey).setPressed(true);
            o.jumpKey.setPressed(player.isOnGround());
            o.sprintKey.setPressed(false);
            holdingKeys = true;
            if (unstuck == 0) {
                o.leftKey.setPressed(false);
                o.rightKey.setPressed(false);
                o.backKey.setPressed(false);
                path = null;
            }
            return Result.WALKING;
        }

        // plan when there's no path, when a partial path is nearly used up (so we don't stop),
        // or when something now blocks the next few steps
        boolean replan = path == null || index >= path.size()
                || (partial && path.size() - index < 4)
                || (ticks % 10 == 0 && blocked(view));
        if (replan) {
            double d = Math.sqrt(feet.getSquaredDistance(toward));
            if (path != null) {
                if (d < bestDist - 1.5) {
                    noProgress = 0;
                } else if (++noProgress > MAX_NO_PROGRESS) {
                    stop(client);
                    return Result.FAILED;
                }
            }
            bestDist = Math.min(bestDist, d);
            path = PathSearch.find(view, toPos(feet), p -> goal.test(toBlock(p)), toPos(toward), MAX_NODES);
            index = 0;
            if (path == null || path.isEmpty()) {
                stop(client);
                return Result.FAILED;
            }
            partial = !goal.test(toBlock(path.get(path.size() - 1)));
        }

        // skip nodes we've already reached (or overshot)
        for (int i = path.size() - 1; i >= index; i--) {
            PathSearch.Pos n = path.get(i);
            double dx = n.x() + 0.5 - player.getX(), dz = n.z() + 0.5 - player.getZ();
            if (dx * dx + dz * dz < 0.35 * 0.35 && Math.abs(n.y() - player.getY()) < 1.2) {
                index = i + 1;
                break;
            }
        }
        if (index >= path.size()) return Result.WALKING;   // re-plan or arrive next tick

        PathSearch.Pos next = path.get(index);

        // steer at the furthest node ahead we can walk to in a straight line on the same level:
        // smooth diagonal lines instead of zig-zagging block to block
        PathSearch.Pos aim = next;
        int level = next.y();
        for (int i = index + 1; i < Math.min(path.size(), index + LOOKAHEAD); i++) {
            PathSearch.Pos n = path.get(i);
            if (n.y() != level || Math.abs(player.getY() - level) > 0.6) break;
            if (!straightLine(view, player.getX(), player.getZ(), n)) break;
            aim = n;
        }
        double dx = aim.x() + 0.5 - player.getX(), dz = aim.z() + 0.5 - player.getZ();

        // face it (turn quickly but smoothly)
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float diff = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        // turn like a person: eased, with momentum, pitch settling toward the horizon
        HumanAim.turnTo(player, targetYaw, 8f, GhostBotClient.turnSpeed * 0.9f, false);

        // how far is the end of the path? slow down (no sprint) for the last few blocks so we don't overshoot
        PathSearch.Pos last = path.get(path.size() - 1);
        double lx = last.x() + 0.5 - player.getX(), lz = last.z() + 0.5 - player.getZ();
        boolean nearEnd = !partial && lx * lx + lz * lz < 3.0 * 3.0;
        // don't sprint into a drop or a jump: sprint momentum makes you miss the landing
        boolean levelAhead = next.y() == level && Math.abs(player.getY() - level) < 0.6;

        double ndx = next.x() + 0.5 - player.getX(), ndz = next.z() + 0.5 - player.getZ();
        boolean facing = Math.abs(diff) < 45f;
        boolean needJump = player.isOnGround()
                && ((next.y() > player.getY() + 0.4 && ndx * ndx + ndz * ndz < 1.8 * 1.8)
                || (player.horizontalCollision && facing));
        o.forwardKey.setPressed(facing);
        o.sprintKey.setPressed(facing && Math.abs(diff) < 20f && !nearEnd && levelAhead);
        o.jumpKey.setPressed(needJump);
        holdingKeys = true;

        // stuck? wiggle free, then re-plan
        if (++stuckTicks % 20 == 0) {
            double moved = Math.abs(player.getX() - lastX) + Math.abs(player.getZ() - lastZ) + Math.abs(player.getY() - lastY);
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            if (moved < 0.3 && stuckTicks >= 40) {
                if (++noProgress > MAX_NO_PROGRESS) {
                    stop(client);
                    return Result.FAILED;
                }
                strafeLeft = !strafeLeft;
                unstuck = 12;
                stuckTicks = 0;
            }
        }
        return Result.WALKING;
    }

    /** Is any of the next few path nodes no longer walkable (a block appeared, a door closed...)? */
    private boolean blocked(PathSearch.World view) {
        for (int i = index; i < Math.min(path.size(), index + 6); i++) {
            if (!PathSearch.walkable(view, path.get(i))) return true;
        }
        return false;
    }

    /**
     * Could the player walk in a straight line from (x, z) to the middle of `to`, staying on
     * level `to.y` the whole way? Checks the player's width (corners of the hitbox) every quarter block.
     */
    private static boolean straightLine(PathSearch.World view, double x, double z, PathSearch.Pos to) {
        double tx = to.x() + 0.5, tz = to.z() + 0.5;
        double len = Math.sqrt((tx - x) * (tx - x) + (tz - z) * (tz - z));
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        for (int i = 1; i <= steps; i++) {
            double px = x + (tx - x) * i / steps, pz = z + (tz - z) * i / steps;
            for (double ox = -0.3; ox <= 0.3; ox += 0.6) {
                for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
                    PathSearch.Pos p = new PathSearch.Pos(MathHelper.floor(px + ox), to.y(), MathHelper.floor(pz + oz));
                    if (!PathSearch.walkable(view, p)) return false;
                }
            }
        }
        return true;
    }

    @Override
    public void stop(MinecraftClient client) {
        if (holdingKeys) {
            GameOptions o = client.options;
            o.forwardKey.setPressed(false);
            o.backKey.setPressed(false);
            o.leftKey.setPressed(false);
            o.rightKey.setPressed(false);
            o.sprintKey.setPressed(false);
            o.jumpKey.setPressed(false);
            KeyBinding.updatePressedStates();
            holdingKeys = false;
        }
    }

    public static BlockPos feet(ClientPlayerEntity player) {
        return BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
    }

    private static PathSearch.Pos toPos(BlockPos p) {
        return new PathSearch.Pos(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos toBlock(PathSearch.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    /** The real world, as the search sees it. */
    static PathSearch.World worldView(ClientWorld world) {
        return new PathSearch.World() {
            final BlockPos.Mutable m = new BlockPos.Mutable();

            private boolean loaded(int x, int z) {
                return world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                if (!loaded(x, z)) return false;
                m.set(x, y, z);
                BlockState s = world.getBlockState(m);
                return s.getCollisionShape(world, m).isEmpty() && s.getFluidState().isEmpty();
            }

            @Override
            public boolean standable(int x, int y, int z) {
                if (!loaded(x, z)) return false;
                m.set(x, y, z);
                BlockState s = world.getBlockState(m);
                VoxelShape shape = s.getCollisionShape(world, m);
                if (shape.isEmpty()) return false;
                double top = shape.getMax(Direction.Axis.Y);
                return top > 0.4 && top <= 1.0;              // no fences / walls (too tall to stand on cleanly)
            }

            @Override
            public boolean dangerous(int x, int y, int z) {
                m.set(x, y, z);
                BlockState s = world.getBlockState(m);
                return s.isOf(Blocks.LAVA) || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.MAGMA_BLOCK)
                        || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.SWEET_BERRY_BUSH)
                        || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE) || s.isOf(Blocks.WITHER_ROSE);
            }
        };
    }
}
