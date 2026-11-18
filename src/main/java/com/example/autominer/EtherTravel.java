package com.example.autominer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gets around with Etherwarp (Aspect of the Void, sneak + right-click on a block):
 * jumps toward the target, then walks the last few blocks. Walks the whole way if
 * there's no AOTV in the hotbar or no good block to land on.
 */
public class EtherTravel implements Mover {

    /** How far to aim (a bit under the usual 57 blocks, to be safe). */
    public static final double RANGE = 55;
    /** Closer than this, just walk. */
    private static final double CLOSE = 8;
    /** A jump has to get at least this much closer to be worth it. */
    private static final double MIN_GAIN = 5;

    private enum Phase { PICK, AIM, SNEAK, WAIT_TP, SETTLE, WALK }

    private final Predicate<BlockPos> goal;
    private final BlockPos target;
    private Phase phase = Phase.PICK;
    private Walker walker = null;
    private BlockPos landing = null;
    private Vec3d aimPoint = null;
    private Vec3d before = null;
    private int timer = 0, fails = 0, prevSlot = -1;
    private boolean sneaking = false;
    private final Set<BlockPos> badLandings = new HashSet<>();

    public EtherTravel(Predicate<BlockPos> goal, BlockPos target) {
        this.goal = goal;
        this.target = target;
    }

    public static EtherTravel to(BlockPos target, double radius) {
        double r2 = radius * radius;
        return new EtherTravel(p -> p.getSquaredDistance(target) <= r2, target);
    }

    @Override
    public Walker.Result tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) return Walker.Result.FAILED;

        BlockPos feet = Walker.feet(player);
        if (phase != Phase.WAIT_TP && goal.test(feet) && player.isOnGround()) {
            stop(client);
            return Walker.Result.ARRIVED;
        }

        if (phase == Phase.WALK) {
            Walker.Result r = walker.tick(client);
            if (r != Walker.Result.WALKING) stop(client);
            return r;
        }

        int slot = findAotv(player);
        double dist = Math.sqrt(feet.getSquaredDistance(target));
        if (phase == Phase.PICK && (slot < 0 || dist <= CLOSE || fails >= 3)) {
            startWalking(client);
            return Walker.Result.WALKING;
        }

        switch (phase) {
            case PICK -> {
                if (!pickLanding(world, player)) {               // nothing useful in view: walk instead
                    startWalking(client);
                    return Walker.Result.WALKING;
                }
                if (prevSlot < 0) prevSlot = player.getInventory().getSelectedSlot();
                player.getInventory().setSelectedSlot(slot);
                releaseMovement(client);
                phase = Phase.AIM;
                timer = 40;
            }
            case AIM -> {
                turnToward(player, aimPoint);
                HitResult hit = player.raycast(RANGE + 4, 1f, false);
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(landing)) {
                    setSneak(client, true);                      // looking right at it: crouch...
                    phase = Phase.SNEAK;
                    timer = 3;
                } else if (--timer <= 0) {                       // couldn't line it up
                    badLandings.add(landing);
                    fails++;
                    phase = Phase.PICK;
                }
            }
            case SNEAK -> {
                turnToward(player, aimPoint);
                setSneak(client, true);
                if (--timer <= 0) {                              // ...and right-click
                    before = new Vec3d(player.getX(), player.getY(), player.getZ());
                    client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                    player.swingHand(Hand.MAIN_HAND);
                    phase = Phase.WAIT_TP;
                    timer = 20;
                }
            }
            case WAIT_TP -> {
                if (timer < 17) setSneak(client, false);
                double moved = before.distanceTo(new Vec3d(player.getX(), player.getY(), player.getZ()));
                if (moved > 3) {                                 // teleported
                    setSneak(client, false);
                    fails = 0;
                    phase = Phase.SETTLE;
                    timer = 4;
                } else if (--timer <= 0) {                       // nothing happened (no mana? blocked?)
                    setSneak(client, false);
                    badLandings.add(landing);
                    fails++;
                    phase = Phase.PICK;
                }
            }
            case SETTLE -> {
                if (--timer <= 0) phase = Phase.PICK;
            }
            default -> { }
        }
        return Walker.Result.WALKING;
    }

    private void startWalking(MinecraftClient client) {
        setSneak(client, false);
        restoreSlot(client);
        walker = new Walker(goal, target);
        phase = Phase.WALK;
    }

    /**
     * Looks around (mostly toward the target) for a block you can see, within range, with two air
     * blocks on top, and picks the one whose landing spot is closest to the target.
     */
    private boolean pickLanding(ClientWorld world, ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        PathSearch.World view = Walker.worldView(world);
        double here = Math.sqrt(Walker.feet(player).getSquaredDistance(target));
        Vec3d toTarget = Vec3d.ofCenter(target).subtract(eye);
        float baseYaw = (float) (Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0);

        BlockPos best = null;
        Vec3d bestHit = null;
        double bestScore = here - MIN_GAIN;
        for (int dy = -88; dy <= 88; dy += 4) {
            for (int dp = -64; dp <= 64; dp += 4) {
                float yaw = baseYaw + dy;
                float pitch = dp;
                Vec3d dir = Vec3d.fromPolar(pitch, yaw);
                BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(dir.multiply(RANGE)),
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, player));
                if (hit.getType() != HitResult.Type.BLOCK) continue;
                BlockPos b = hit.getBlockPos();
                if (badLandings.contains(b)) continue;
                if (!view.standable(b.getX(), b.getY(), b.getZ())) continue;
                if (!view.passable(b.getX(), b.getY() + 1, b.getZ()) || !view.passable(b.getX(), b.getY() + 2, b.getZ())) continue;
                if (view.dangerous(b.getX(), b.getY(), b.getZ())) continue;
                double score = Math.sqrt(b.up().getSquaredDistance(target));
                if (score < bestScore) {
                    bestScore = score;
                    best = b.toImmutable();
                    bestHit = hit.getPos();
                }
            }
        }
        if (best == null) return false;
        landing = best;
        // aim a little into the block from where the ray hit it, so the crosshair lands on it
        aimPoint = bestHit.add(Vec3d.ofCenter(best).subtract(bestHit).multiply(0.15));
        return true;
    }

    private static void turnToward(ClientPlayerEntity player, Vec3d point) {
        Vec3d eye = player.getEyePos();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dyaw = MathHelper.wrapDegrees(yaw - player.getYaw());
        float dpitch = pitch - player.getPitch();
        // fast at first, slowing down as it lines up
        float sy = Math.abs(dyaw) < 0.3f ? dyaw : Math.copySign(MathHelper.clamp(Math.abs(dyaw) * 0.45f, 0.3f, 35f), dyaw);
        float sp = Math.abs(dpitch) < 0.3f ? dpitch : Math.copySign(MathHelper.clamp(Math.abs(dpitch) * 0.45f, 0.3f, 35f), dpitch);
        player.setYaw(player.getYaw() + sy);
        player.setPitch(MathHelper.clamp(player.getPitch() + sp, -90f, 90f));
        player.setHeadYaw(player.getYaw());
    }

    /** Hotbar slot holding an Aspect of the Void, or -1. */
    public static int findAotv(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String name = Formatting.strip(s.getName().getString());
            if (name != null && name.toLowerCase(Locale.ROOT).contains("aspect of the void")) return i;
            NbtComponent custom = s.get(DataComponentTypes.CUSTOM_DATA);
            if (custom != null) {
                String id = custom.copyNbt().getString("id", "");
                if (id.equalsIgnoreCase("ASPECT_OF_THE_VOID")) return i;
                if (custom.copyNbt().getCompoundOrEmpty("ExtraAttributes").getString("id", "")
                        .equalsIgnoreCase("ASPECT_OF_THE_VOID")) return i;
            }
        }
        return -1;
    }

    private void setSneak(MinecraftClient client, boolean on) {
        client.options.sneakKey.setPressed(on);
        sneaking = on;
    }

    private static void releaseMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void restoreSlot(MinecraftClient client) {
        if (prevSlot >= 0 && client.player != null) {
            client.player.getInventory().setSelectedSlot(prevSlot);
            prevSlot = -1;
        }
    }

    @Override
    public void stop(MinecraftClient client) {
        if (sneaking) setSneak(client, false);
        if (walker != null) walker.stop(client);
        restoreSlot(client);
    }
}
