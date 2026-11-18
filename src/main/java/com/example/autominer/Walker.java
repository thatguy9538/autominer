package com.example.autominer;

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
    private static final int MAX_REPLANS = 8;

    private final Predicate<BlockPos> goal;
    private final BlockPos toward;
    private List<PathSearch.Pos> path;
    private int index = 0;
    private int replans = 0;
    private int stuckTicks = 0;
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

        BlockPos feet = feet(player);
        if (goal.test(feet) && player.isOnGround()) {
            stop(client);
            return Result.ARRIVED;
        }

        if (path == null || index >= path.size()) {
            if (path != null && ++replans > MAX_REPLANS) {    // walked a partial path several times: give up
                stop(client);
                return Result.FAILED;
            }
            path = PathSearch.find(worldView(world), toPos(feet), p -> goal.test(toBlock(p)), toPos(toward), MAX_NODES);
            index = 0;
            stuckTicks = 0;
            if (path == null || path.isEmpty()) {
                stop(client);
                return Result.FAILED;
            }
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
        double dx = next.x() + 0.5 - player.getX(), dz = next.z() + 0.5 - player.getZ();

        // face the next node (turn quickly but smoothly)
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float diff = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float step = Math.abs(diff) <= 2f ? diff : Math.copySign(MathHelper.clamp(Math.abs(diff) * 0.5f, 2f, 40f), diff);
        player.setYaw(player.getYaw() + step);
        player.setHeadYaw(player.getYaw());
        player.setPitch(player.getPitch() + (8f - player.getPitch()) * 0.3f);

        boolean facing = Math.abs(diff) < 45f;
        boolean needJump = player.isOnGround()
                && (next.y() > player.getY() + 0.4 || (player.horizontalCollision && facing));
        GameOptions o = client.options;
        o.forwardKey.setPressed(facing);
        o.sprintKey.setPressed(facing && Math.abs(diff) < 20f);
        o.jumpKey.setPressed(needJump);
        holdingKeys = true;

        // stuck? re-plan
        if (++stuckTicks % 20 == 0) {
            double moved = Math.abs(player.getX() - lastX) + Math.abs(player.getZ() - lastZ) + Math.abs(player.getY() - lastY);
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            if (moved < 0.3 && stuckTicks >= 40) {
                if (++replans > MAX_REPLANS) {
                    stop(client);
                    return Result.FAILED;
                }
                path = null;
            }
        }
        return Result.WALKING;
    }

    @Override
    public void stop(MinecraftClient client) {
        if (holdingKeys) {
            GameOptions o = client.options;
            o.forwardKey.setPressed(false);
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
