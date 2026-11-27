package com.example.ghostbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Spreads each tick's camera turn over the frames drawn until the next tick, so the camera
 * glides at your frame rate instead of jumping 20 times a second. Each frame's piece is snapped
 * to whole mouse-sensitivity steps and applied with Minecraft's own mouse-look function
 * (Entity.changeLookDirection), exactly the way a real mouse turns the camera.
 */
public final class Glide {

    private Glide() {
    }

    private static final double TICK_NS = 50_000_000.0;
    /** This tick's turn, how much of it is already applied, and when it started. */
    private static float totalYaw, totalPitch, doneYaw, donePitch;
    private static long startNs = 0;
    /** Part of a mouse step not applied yet (carried to the next frame). */
    private static float resYaw, resPitch;

    /** From the tick: turn this much over the next 50 ms (anything left from last tick is added). */
    public static void queue(float dYaw, float dPitch) {
        float leftYaw = totalYaw - doneYaw, leftPitch = totalPitch - donePitch;
        totalYaw = leftYaw + dYaw;
        totalPitch = leftPitch + dPitch;
        doneYaw = donePitch = 0;
        startNs = System.nanoTime();
    }

    /** Every frame (before it's drawn). */
    public static void frame() {
        if (startNs == 0) return;
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) {
            clear();
            return;
        }
        double frac = Math.min(1.0, (System.nanoTime() - startNs) / TICK_NS);
        float wantYaw = (float) (totalYaw * frac), wantPitch = (float) (totalPitch * frac);
        float dYaw = wantYaw - doneYaw + resYaw, dPitch = wantPitch - donePitch + resPitch;
        doneYaw = wantYaw;
        donePitch = wantPitch;

        // whole mouse counts only; keep the rest for the next frame
        float g = HumanAim.sensitivityStep();
        int cx = Math.round(dYaw / g), cy = Math.round(dPitch / g);
        resYaw = dYaw - cx * g;
        resPitch = dPitch - cy * g;
        if (cx == 0 && cy == 0) return;
        // vanilla mouse look: yaw += x * 0.15, pitch += y * 0.15 (clamped), and it moves last-frame angles too
        p.changeLookDirection(cx * g / 0.15, cy * g / 0.15);
        p.setHeadYaw(p.getYaw());
    }

    public static void clear() {
        totalYaw = totalPitch = doneYaw = donePitch = resYaw = resPitch = 0;
        startNs = 0;
    }
}
