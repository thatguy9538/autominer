package com.example.powderassist;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Smooth camera turning: fast when far off, slowing down as it lines up. */
public final class Aim {

    private Aim() {
    }

    /**
     * Turns the camera part of the way toward a point. `fraction` is how much of the remaining
     * angle to cover this tick (0..1), `maxStep` caps degrees per tick.
     * Returns how far off (degrees) the camera still is after turning.
     */
    public static float turnToward(ClientPlayerEntity player, Vec3d point, float fraction, float maxStep) {
        Vec3d eye = player.getEyePos();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        float dyaw = MathHelper.wrapDegrees(yaw - player.getYaw());
        float dpitch = pitch - player.getPitch();
        player.setYaw(player.getYaw() + step(dyaw, fraction, maxStep));
        player.setPitch(MathHelper.clamp(player.getPitch() + step(dpitch, fraction, maxStep), -90f, 90f));
        player.setHeadYaw(player.getYaw());
        float ry = MathHelper.wrapDegrees(yaw - player.getYaw()), rp = pitch - player.getPitch();
        return (float) Math.sqrt(ry * ry + rp * rp);
    }

    private static float step(float diff, float fraction, float maxStep) {
        if (Math.abs(diff) < 0.15f) return diff;
        float s = Math.abs(diff) * fraction;
        s = MathHelper.clamp(s, 0.15f, maxStep);
        if (s > Math.abs(diff)) s = Math.abs(diff);
        return Math.copySign(s, diff);
    }

    /** Angle in degrees between where the player looks and the direction to a point. */
    public static float angleTo(ClientPlayerEntity player, Vec3d point) {
        Vec3d to = point.subtract(player.getEyePos()).normalize();
        Vec3d look = player.getRotationVec(1f);
        double dot = MathHelper.clamp(look.dotProduct(to), -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(dot));
    }
}
