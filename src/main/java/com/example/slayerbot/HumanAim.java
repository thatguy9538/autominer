package com.example.slayerbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Human-like camera movement, built from motor-control research and open-source aiming code:
 *
 * 1. Reaction time: a new target is noticed after an ex-Gaussian delay (normal + exponential tail),
 *    the standard model for human reaction times.
 * 2. Aimed movements ("flicks") follow the minimum-jerk trajectory (Flash & Hogan 1985):
 *    s(t) = 10t^3 - 15t^4 + 6t^5, a bell-shaped speed profile.
 * 3. Duration comes from Fitts's law, MT = a + b * log2(A / W + 1): bigger turns and smaller
 *    targets take longer, varied a little each time.
 * 4. Each flick is a primary submovement that lands near the target with an error that grows
 *    with its size (Meyer et al. 1988), slightly short on average, then a pause and a smaller
 *    corrective submovement if it's still off.
 * 5. Paths curve: pitch starts a little after yaw, plus WindMouse's "wind" (Benjamin Land,
 *    GPL-3.0): a correlated random push sideways that fades out before the end.
 * 6. Close to the target it switches to smooth pursuit: follows the target's motion with a
 *    short lag and gain below 1, and makes catch-up flicks (riding on top of the following
 *    motion, as eye/hand catch-up movements do) when the error gets too big for the speed.
 * 7. Small, slow drift (Ornstein-Uhlenbeck) and a faint tremor, plus rare 1-2 tick hesitations.
 * 8. Aim point inside the hitbox from LiquidBounce's Gaussian point processor (GPL-3.0): offsets
 *    drawn from its fitted means / standard deviations and eased toward, with its "lazy" rule
 *    of not moving the aim point for tiny changes.
 * 9. The turn is handed to Glide, which spreads it across every frame until the next tick and
 *    snaps each frame's piece to whole mouse-sensitivity steps (Minecraft: f = sens * 0.6 + 0.2,
 *    step = f^3 * 8 * 0.15 degrees; LiquidBounce's GCD fix), applied through vanilla mouse look.
 */
public final class HumanAim {

    private HumanAim() {
    }

    private static final Random RNG = new Random();

    private enum Mode { REACT, MOVE, VERIFY, TRACK }

    private static Mode mode = Mode.TRACK;
    private static Entity lastTarget = null;
    private static int waitTicks = 0;

    // current flick plan (offsets relative to where the flick started)
    private static float planYaw, planPitch, planLat, perpYaw, perpPitch, pitchLag;
    private static int planN, planK;
    private static float doneYaw, donePitch;
    private static float windVY, windVP, windY, windP, windMag;

    // smooth pursuit
    private static float vYaw, vPitch;
    /** Following speed carried through waits and flicks (a hand keeps tracking while it corrects). */
    private static float pvYaw, pvPitch;
    private static final float[] histYaw = new float[4], histPitch = new float[4];
    private static int histN = 0;
    private static int stopTicks = 0;

    // noise
    private static float ouYaw, ouPitch, trYaw, trPitch;
    // sub-step remainder from snapping to mouse-sensitivity steps
    private static float resYaw, resPitch;

    // aim point inside the hitbox (LiquidBounce Gaussian constants)
    private static final double MEAN_X = 0.00942273861037109, SD_X = 0.23319837528201348;
    private static final double MEAN_Y = -0.30075078007595923, SD_Y = 0.3492437109081718;
    private static final double MEAN_Z = 0.013282929419023442, SD_Z = 0.24453708645460387;
    private static double offX, offY, offZ, goalX, goalY, goalZ, offSpeed = 0.15;
    private static Vec3d lazyPoint = null;
    private static double lazyThreshold = 0.15;

    public static void reset() {
        mode = Mode.TRACK;
        lastTarget = null;
        vYaw = vPitch = 0;
        histN = 0;
        resYaw = resPitch = 0;
        lazyPoint = null;
        Glide.clear();
    }

    /** Aim at an entity (a target in a fight). Returns degrees still off. */
    public static float lookAt(ClientPlayerEntity p, Entity target, float maxDegPerSec) {
        if (target != lastTarget) {
            lastTarget = target;
            pvYaw = pvPitch = 0;
            mode = Mode.REACT;
            waitTicks = reactionTicks();
            histN = 0;
            lazyPoint = null;
            newOffsetGoal();
            offX = goalX;
            offY = goalY;
            offZ = goalZ;
        }
        // ease the aim point toward its Gaussian goal; pick a new goal once it's reached
        double dx = goalX - offX, dy = goalY - offY, dz = goalZ - offZ;
        if (dx * dx + dy * dy + dz * dz < 0.05 * 0.05) newOffsetGoal();
        offX += dx * offSpeed;
        offY += dy * offSpeed;
        offZ += dz * offSpeed;

        Box b = target.getBoundingBox();
        double hw = (b.maxX - b.minX) / 2, h = b.maxY - b.minY;
        Vec3d point = new Vec3d(
                (b.minX + b.maxX) / 2 + MathHelper.clamp(offX, -0.85, 0.85) * hw,
                b.minY + MathHelper.clamp(0.78 + offY * 0.5, 0.15, 0.95) * h,
                (b.minZ + b.maxZ) / 2 + MathHelper.clamp(offZ, -0.85, 0.85) * hw);
        // lazy: don't move the aim point for changes smaller than 10-20 cm
        if (lazyPoint == null || lazyPoint.squaredDistanceTo(point) > lazyThreshold * lazyThreshold) {
            lazyPoint = point;
            lazyThreshold = 0.1 + RNG.nextDouble() * 0.1;
        }
        Vec3d eye = p.getEyePos();
        double ax = lazyPoint.x - eye.x, ay = lazyPoint.y - eye.y, az = lazyPoint.z - eye.z;
        double dist = Math.max(0.5, Math.sqrt(ax * ax + ay * ay + az * az));
        float width = (float) Math.toDegrees(2 * Math.atan(Math.max(0.2, hw) / dist));   // the target's angular size
        float yaw = (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(ay, Math.sqrt(ax * ax + az * az)));
        return step(p, yaw, pitch, maxDegPerSec, Math.max(1f, width), true);
    }

    /** Turn toward a direction (walking). Returns degrees still off. */
    public static float turnTo(ClientPlayerEntity p, float yaw, float pitch, float maxDegPerSec, boolean tremor) {
        if (lastTarget != null) {                       // switched from fighting to walking: no reaction delay
            lastTarget = null;
            if (mode == Mode.REACT) mode = Mode.TRACK;
            histN = 0;
        }
        return step(p, yaw, pitch, maxDegPerSec, 8f, tremor);
    }

    // ------------------------------------------------------------------ the controller

    private static float step(ClientPlayerEntity p, float tYaw, float tPitch, float maxDegPerSec, float width, boolean tremor) {
        float eYaw = MathHelper.wrapDegrees(tYaw - p.getYaw());
        float ePitch = tPitch - p.getPitch();
        float err = (float) Math.sqrt(eYaw * eYaw + ePitch * ePitch);
        pushHistory(tYaw, tPitch);
        float maxStep = maxDegPerSec / 20f;
        float dYaw = 0, dPitch = 0;

        // the target's motion as seen ~100 ms ago (degrees per tick)
        float tvY = 0, tvP = 0;
        if (histN >= 4) {
            tvY = MathHelper.wrapDegrees(histYaw[1] - histYaw[0]);
            tvP = histPitch[1] - histPitch[0];
        }
        float tSpeed = (float) Math.sqrt(tvY * tvY + tvP * tvP);
        boolean moving = tSpeed > 0.3f;
        float catchUp = Math.max(Math.max(4f, width * 1.2f), 2.5f * tSpeed);

        if (mode == Mode.REACT || mode == Mode.VERIFY) {
            // keep following the target's motion while waiting (zero for a brand-new target)
            if (histN >= 4) {
                pvYaw += (0.9f * tvY - pvYaw) * 0.5f;
                pvPitch += (0.9f * tvP - pvPitch) * 0.5f;
            }
            dYaw = pvYaw;
            dPitch = pvPitch;
        }
        if (mode == Mode.REACT) {
            if (--waitTicks <= 0) {
                if (err > 0.5f) plan(eYaw, ePitch, err, width, maxDegPerSec, true);
                else mode = Mode.TRACK;
            }
        } else if (mode == Mode.MOVE) {
            planK++;
            float t = (float) planK / planN;
            float s = minJerk(t);
            float sp = minJerk(MathHelper.clamp((t - pitchLag) / (1 - pitchLag), 0, 1));   // pitch starts a bit later
            // WindMouse wind: correlated random push, faded out by the end of the movement
            windVY = windVY / 1.7320508f + (RNG.nextFloat() * 2 - 1) * windMag / 2.236068f;
            windVP = windVP / 1.7320508f + (RNG.nextFloat() * 2 - 1) * windMag * 0.6f / 2.236068f;
            windY += windVY;
            windP += windVP;
            float fade = 1 - s;
            float bump = (float) Math.sin(Math.PI * t) * planLat;
            float wantYaw = planYaw * s + perpYaw * bump + windY * fade;
            float wantPitch = planPitch * sp + perpPitch * bump + windP * fade;
            // the flick rides on top of the ongoing following motion
            dYaw = wantYaw - doneYaw + pvYaw;
            dPitch = wantPitch - donePitch + pvPitch;
            doneYaw = wantYaw;
            donePitch = wantPitch;
            pvYaw += (0.9f * tvY - pvYaw) * 0.5f;
            pvPitch += (0.9f * tvP - pvPitch) * 0.5f;
            if (planK >= planN) {
                mode = Mode.VERIFY;
                waitTicks = 1 + RNG.nextInt(3);         // 50-150 ms to see where it landed
            }
        } else if (mode == Mode.VERIFY) {
            if (--waitTicks <= 0) {
                float tol = moving ? catchUp : Math.max(0.6f, width * 0.3f);
                if (err > tol) plan(eYaw, ePitch, err, width, maxDegPerSec, false);
                else {
                    mode = Mode.TRACK;
                    vYaw = vPitch = 0;
                }
            }
        }
        if (mode == Mode.TRACK) {
            if (err > catchUp) {                        // fell too far behind: catch-up flick after a short latency
                mode = Mode.REACT;
                waitTicks = 1 + RNG.nextInt(2);
            } else {
                if (stopTicks > 0) {                    // a brief hesitation
                    stopTicks--;
                    vYaw *= 0.3f;
                    vPitch *= 0.3f;
                } else {
                    if (RNG.nextFloat() < 0.015f) stopTicks = 1 + RNG.nextInt(2);
                    // smooth pursuit: target's motion (gain 0.9) + correct the position error
                    vYaw += (0.9f * tvY + 0.32f * eYaw - vYaw) * 0.55f;
                    vPitch += (0.9f * tvP + 0.24f * ePitch - vPitch) * 0.5f;
                }
                dYaw = vYaw;
                dPitch = vPitch;
                pvYaw = vYaw;
                pvPitch = vPitch;
            }
        }

        // slow drift (Ornstein-Uhlenbeck, tau ~0.6 s, ~0.12 deg) and faint tremor, added as changes
        float ouY0 = ouYaw, ouP0 = ouPitch;
        ouYaw += -ouYaw * 0.083f + 0.049f * (float) RNG.nextGaussian();
        ouPitch += -ouPitch * 0.083f + 0.037f * (float) RNG.nextGaussian();
        dYaw += ouYaw - ouY0;
        dPitch += ouPitch - ouP0;
        if (tremor) {
            float trY0 = trYaw, trP0 = trPitch;
            trYaw = 0.35f * trYaw + 0.035f * (float) RNG.nextGaussian();
            trPitch = 0.35f * trPitch + 0.028f * (float) RNG.nextGaussian();
            dYaw += trYaw - trY0;
            dPitch += trPitch - trP0;
        }

        dYaw = MathHelper.clamp(dYaw, -maxStep, maxStep);
        dPitch = MathHelper.clamp(dPitch, -maxStep, maxStep);
        apply(p, dYaw, dPitch);
        return err;
    }

    /** Plans one aimed submovement toward an error of (eYaw, ePitch). */
    private static void plan(float eYaw, float ePitch, float a, float width, float maxDegPerSec, boolean isPrimary) {
        float ux = eYaw / a, uy = ePitch / a;
        perpYaw = -uy;
        perpPitch = ux;
        // Fitts's law duration, scaled by the speed setting (400 deg/s = 1x), varied ~12%
        double id = Math.log(a / width + 1) / Math.log(2);
        double mt = (0.10 + 0.085 * id) * MathHelper.clamp(400.0 / maxDegPerSec, 0.5, 3.0);
        mt *= Math.exp(RNG.nextGaussian() * 0.12);
        mt = Math.max(mt, 1.875 * a / maxDegPerSec);    // min-jerk peak speed must stay under the cap
        planN = Math.max(2, (int) Math.round(mt / 0.05));
        // where it lands: error grows with the turn; primaries land a little short on average
        float sd = (isPrimary ? 0.055f : 0.035f) * a + 0.08f;
        float bias = isPrimary ? (RNG.nextFloat() < 0.25f ? 0.03f * a : -0.025f * a) : 0f;
        float along = a + bias + (float) RNG.nextGaussian() * sd;
        float across = (float) RNG.nextGaussian() * sd * 0.6f;
        planYaw = ux * along + perpYaw * across;
        planPitch = uy * along + perpPitch * across;
        planLat = MathHelper.clamp((float) RNG.nextGaussian() * 0.035f * a, -0.09f * a, 0.09f * a);
        pitchLag = isPrimary ? 0.06f + RNG.nextFloat() * 0.1f : 0.02f;
        windMag = 0.012f * a;
        windVY = windVP = windY = windP = 0;
        doneYaw = donePitch = 0;
        planK = 0;
        mode = Mode.MOVE;
    }

    private static float minJerk(float t) {
        return t * t * t * (10 - 15 * t + 6 * t * t);
    }

    /** Reaction time: ex-Gaussian (mu 190 ms, sigma 25 ms, tau 60 ms), clamped to 130-600 ms. */
    private static int reactionTicks() {
        double ms = 190 + RNG.nextGaussian() * 25 - 60 * Math.log(1 - RNG.nextDouble());
        ms = MathHelper.clamp(ms, 130, 600);
        return Math.max(1, (int) Math.round(ms / 50));
    }

    private static void newOffsetGoal() {
        goalX = MEAN_X + RNG.nextGaussian() * SD_X;
        goalY = MEAN_Y + RNG.nextGaussian() * SD_Y;
        goalZ = MEAN_Z + RNG.nextGaussian() * SD_Z;
        offSpeed = 0.1 + RNG.nextDouble() * 0.1;
    }

    private static void pushHistory(float yaw, float pitch) {
        System.arraycopy(histYaw, 1, histYaw, 0, 3);
        System.arraycopy(histPitch, 1, histPitch, 0, 3);
        histYaw[3] = yaw;
        histPitch[3] = pitch;
        if (histN < 4) histN++;
    }

    /** Hands the turn to Glide, which spreads it over the frames until the next tick (snapped per frame). */
    private static void apply(ClientPlayerEntity p, float dYaw, float dPitch) {
        // keep pitch inside -90..90 counting what's still waiting to be applied
        Glide.queue(dYaw, dPitch);
    }

    /** Degrees one mouse count turns the camera at your sensitivity (vanilla: f^3 * 8 * 0.15). */
    static float sensitivityStep() {
        double sens = 0.5;
        try {
            sens = MinecraftClient.getInstance().options.getMouseSensitivity().getValue();
        } catch (Exception ignored) {
        }
        double f = sens * 0.6 + 0.2;
        return (float) Math.max(0.001, f * f * f * 8.0 * 0.15);
    }
}
