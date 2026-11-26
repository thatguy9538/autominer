package com.example.slayerbot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Camera movement that looks like a hand on a mouse:
 *  - eases in and out: turning speeds up, then slows into the target (with momentum, so a fast
 *    turn can overshoot a little and come back),
 *  - pitch reacts slower than yaw, so turns follow a slight curve instead of a straight line,
 *  - aims at a spot on the target that drifts around its body, not the exact center,
 *  - a small, smooth hand tremor while tracking,
 *  - a short reaction delay before turning to a new target,
 *  - each turn's top speed varies a little.
 */
public final class HumanAim {

    private HumanAim() {
    }

    private static final Random RNG = new Random();

    /** Current turning speed in degrees per tick (the "momentum"). */
    private static float vYaw = 0, vPitch = 0;
    /** This turn's speed factor (0.85-1.15), re-rolled for every new target. */
    private static float speedRoll = 1f;
    /** Ticks to wait before reacting to a new target. */
    private static int reactTicks = 0;
    /** Where on the target's body we aim (fractions of its size), and where that spot is drifting to. */
    private static double offX = 0, offY = 0.65, offZ = 0, goalX = 0, goalY = 0.65, goalZ = 0;
    private static int offTicks = 0;
    /** Tremor phases, so each session's shake is different. */
    private static final double P1 = RNG.nextDouble() * 6.28, P2 = RNG.nextDouble() * 6.28, P3 = RNG.nextDouble() * 6.28;
    private static long t = 0;
    private static Entity lastTarget = null;

    public static void reset() {
        vYaw = vPitch = 0;
        lastTarget = null;
        reactTicks = 0;
    }

    /** Aim at an entity. Returns how many degrees off the crosshair still is. */
    public static float lookAt(ClientPlayerEntity p, Entity target, float maxDegPerSec) {
        if (target != lastTarget) {
            lastTarget = target;
            reactTicks = 2 + RNG.nextInt(4);           // 100-250 ms to notice and start moving
            speedRoll = 0.85f + RNG.nextFloat() * 0.3f;
            pickSpot();
            offX = goalX;
            offY = goalY;
            offZ = goalZ;
        }
        // let the aim spot wander around the body
        if (--offTicks <= 0) pickSpot();
        offX += (goalX - offX) * 0.06;
        offY += (goalY - offY) * 0.06;
        offZ += (goalZ - offZ) * 0.06;
        Box b = target.getBoundingBox();
        Vec3d point = new Vec3d(
                (b.minX + b.maxX) / 2 + offX * (b.maxX - b.minX),
                b.minY + offY * (b.maxY - b.minY),
                (b.minZ + b.maxZ) / 2 + offZ * (b.maxZ - b.minZ));
        return lookAt(p, point, maxDegPerSec);
    }

    /** Aim at a point. Returns how many degrees off the crosshair still is. */
    public static float lookAt(ClientPlayerEntity p, Vec3d point, float maxDegPerSec) {
        Vec3d eye = p.getEyePos();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return turnTo(p, yaw, pitch, maxDegPerSec, true);
    }

    /** Turn toward a yaw / pitch (used for walking too). Returns degrees still off. */
    public static float turnTo(ClientPlayerEntity p, float yaw, float pitch, float maxDegPerSec, boolean tremor) {
        t++;
        float eYaw = MathHelper.wrapDegrees(yaw - p.getYaw());
        float ePitch = pitch - p.getPitch();
        float off = (float) Math.sqrt(eYaw * eYaw + ePitch * ePitch);

        if (reactTicks > 0) {                          // still "reacting": just let momentum bleed off
            reactTicks--;
            vYaw *= 0.6f;
            vPitch *= 0.6f;
        } else {
            float max = maxDegPerSec / 20f * speedRoll;
            // the speed we'd like: big error -> fast, small error -> slow (eases out), curved toward small errors
            float wantYaw = Math.copySign(Math.min(max, 0.55f * (float) Math.pow(Math.abs(eYaw), 0.85)), eYaw);
            float wantPitch = Math.copySign(Math.min(max * 0.75f, 0.38f * (float) Math.pow(Math.abs(ePitch), 0.85)), ePitch);
            // momentum: speed can only change so much per tick (eases in, and can overshoot slightly)
            float accel = Math.max(1.2f, max * 0.28f);
            vYaw += MathHelper.clamp(wantYaw - vYaw, -accel, accel);
            vPitch += MathHelper.clamp(wantPitch - vPitch, -accel * 0.7f, accel * 0.7f);
        }

        float jy = 0, jp = 0;
        if (tremor) {                                  // smooth hand shake, smaller when moving fast
            float calm = 1f / (1f + Math.abs(vYaw) * 0.25f);
            jy = (float) (0.09 * Math.sin(t * 0.37 + P1) + 0.05 * Math.sin(t * 0.91 + P2)) * calm;
            jp = (float) (0.07 * Math.sin(t * 0.29 + P3) + 0.04 * Math.sin(t * 0.73 + P1)) * calm;
        }
        p.setYaw(p.getYaw() + vYaw + jy);
        p.setPitch(MathHelper.clamp(p.getPitch() + vPitch + jp, -90f, 90f));
        p.setHeadYaw(p.getYaw());
        return off;
    }

    private static void pickSpot() {
        goalX = (RNG.nextDouble() - 0.5) * 0.45;
        goalZ = (RNG.nextDouble() - 0.5) * 0.45;
        goalY = 0.45 + RNG.nextDouble() * 0.4;          // chest to head height
        offTicks = 20 + RNG.nextInt(40);               // every 1-3 seconds
    }
}
