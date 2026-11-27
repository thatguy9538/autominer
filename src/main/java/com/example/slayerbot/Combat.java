package com.example.slayerbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fighting: pick a target, turn to it smoothly, walk or path-find to it, and attack at the set
 * clicks per second with a little random jitter.
 *
 * Adapted from V5's CombatBot (https://github.com/V5-Client/V5, modules/combat/CombatBot.js),
 * GPL-3.0: target scoring (distance + turn), the 4-block reach, switching to path-finding past a
 * threshold, re-pathing when the target moves 7+ blocks, blacklisting targets the path can't
 * reach for 5 s, and the 0.82-1.18 attack jitter.
 */
public final class Combat {

    private Combat() {
    }

    // from V5 CombatBot
    static final double ATTACK_REACH = 4.0;
    static final double REPATH_DISTANCE = 7.0;
    static final long REPATH_DELAY_MS = 1200;
    static final long PATH_FAIL_BLACKLIST_MS = 5000;
    static final double NAMETAG_RANGE = 2.5;

    public enum State { IDLE, PATHING, FIGHTING }

    public static State state = State.IDLE;
    public static Entity target = null;
    /** How many targets the last scan found (for the HUD). */
    public static int found = 0;
    private static final Map<Entity, Integer> levels = new HashMap<>();
    private static final Pattern LEVEL = Pattern.compile("lv\\.?\\s*(\\d+)");
    private static int idleTicks = 0;
    private static Vec3d roamGoal = null;

    private static Walker walker = null;
    private static Vec3d pathTarget = null;
    private static long pathStartedAt = 0, nextAttackAt = 0;
    private static final Map<UUID, Long> blacklist = new HashMap<>();
    /** The yaw / pitch we last set, so the failsafe can tell our turning from someone else's. */
    public static float setYaw = Float.NaN, setPitch = Float.NaN;

    /**
     * One tick of fighting. In the boss stage only your own boss is a target; otherwise the
     * slayer's XP mobs inside the leash.
     */
    public static void tick(MinecraftClient client, boolean bossStage) {
        ClientPlayerEntity p = client.player;
        long now = System.currentTimeMillis();
        blacklist.values().removeIf(t -> t < now);

        List<Entity> targets = bossStage ? ownBoss(client) : xpMobs(client);
        found = targets.size();
        if (!targets.isEmpty()) idleTicks = 0;
        if (target != null && (!usable(client, target) || !targets.contains(target))) setTarget(client, null);
        if (target == null) {
            setTarget(client, best(p, targets, bossStage));
            if (target == null) {
                if (bossStage) {
                    // the boss may not be in range yet: walk to your "Spawned by" tag if you can see it
                    Entity tag = ownTag(client);
                    if (tag != null && p.distanceTo(tag) > 6) startPath(client, pos(tag));
                    else stopMoving(client);
                } else {
                    roam(client);                       // nothing in range: go look for spawns instead of waiting
                }
                return;
            }
            roamGoal = null;
        }

        Vec3d pos = pos(target);
        double dist = p.getEyePos().distanceTo(aimPoint(target));
        double flat = Math.sqrt(sq(pos.x - p.getX()) + sq(pos.z - p.getZ()));

        if (state == State.PATHING && walker != null) {
            // follow the path; re-plan if the target ran 7+ blocks from where we were heading
            if (now - pathStartedAt >= REPATH_DELAY_MS && pathTarget != null && flatDist(pos, pathTarget) >= REPATH_DISTANCE) {
                startPath(client, pos);
                return;
            }
            Walker.Result r = walker.tick(client);
            if (r == Walker.Result.FAILED) {
                blacklist.put(target.getUuid(), now + PATH_FAIL_BLACKLIST_MS);
                setTarget(client, null);
                return;
            }
            if (r == Walker.Result.ARRIVED || (dist <= ATTACK_REACH + 1.5 && p.canSee(target))) {
                walker.stop(client);
                walker = null;
                state = State.FIGHTING;
            } else {
                return;
            }
        }

        int threshold = SlayerBotClient.pathThreshold + (state == State.FIGHTING ? 2 : 0);
        if (flat > threshold || (dist > ATTACK_REACH && !p.canSee(target))) {
            startPath(client, pos);
            return;
        }
        engage(client, p, pos, dist, flat, now);
    }

    private static void engage(MinecraftClient client, ClientPlayerEntity p, Vec3d pos, double dist, double flat, long now) {
        state = State.FIGHTING;
        HumanAim.lookAt(p, target, SlayerBotClient.turnSpeed);
        GameOptions o = client.options;
        // only walk once you're actually facing the mob: walking while the camera is still turning
        // makes you run circles around it
        double toYaw = Math.toDegrees(Math.atan2(pos.z - p.getZ(), pos.x - p.getX())) - 90.0;
        float facing = Math.abs(MathHelper.wrapDegrees((float) toYaw - p.getYaw()));
        boolean walk = flat > 2.8 && facing < 35f;
        boolean sprint = walk && facing < 15f && flat > 4.5;
        o.forwardKey.setPressed(walk);
        o.sprintKey.setPressed(sprint);
        o.backKey.setPressed(false);
        o.jumpKey.setPressed(p.isOnGround() && walk && (pos.y - p.getY() > 1.5 || p.horizontalCollision));

        // attack (not while the ability item is in your hand)
        if (Ability.busy() || dist > ATTACK_REACH + 0.35 || now < nextAttackAt) return;
        boolean onTarget = client.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() == target;
        if (SlayerBotClient.rightClick) {
            if (angleTo(p, aimPoint(target)) > 12) return;
            client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        } else {
            if (!onTarget) return;
            client.interactionManager.attackEntity(p, target);
        }
        p.swingHand(Hand.MAIN_HAND);
        double jitter = 0.82 + Math.random() * 0.36;
        nextAttackAt = now + Math.round(1000.0 / SlayerBotClient.cps * jitter);
    }

    private static void startPath(MinecraftClient client, Vec3d pos) {
        if (walker != null) walker.stop(client);
        stopKeys(client);
        walker = Walker.to(BlockPos.ofFloored(pos), 2.5);
        pathTarget = pos;
        pathStartedAt = System.currentTimeMillis();
        state = State.PATHING;
    }

    static void setTarget(MinecraftClient client, Entity t) {
        if (t == target) return;
        if (walker != null) walker.stop(client);
        walker = null;
        pathTarget = null;
        target = t;
        nextAttackAt = 0;
        state = State.IDLE;
    }

    public static void stopMoving(MinecraftClient client) {
        if (walker != null) walker.stop(client);
        walker = null;
        stopKeys(client);
        state = State.IDLE;
    }

    public static void reset(MinecraftClient client) {
        stopMoving(client);
        target = null;
        roamGoal = null;
        idleTicks = 0;
        blacklist.clear();
        HumanAim.reset();
        setYaw = setPitch = Float.NaN;
    }

    private static void stopKeys(MinecraftClient client) {
        GameOptions o = client.options;
        o.forwardKey.setPressed(false);
        o.sprintKey.setPressed(false);
        o.jumpKey.setPressed(false);
    }

    // ------------------------------------------------------------------ choosing targets

    /**
     * V5's score (distance plus a little for every degree you'd have to turn), and for XP mobs a
     * bonus for standing in a group (more kills per trip) and for a higher level (more XP per kill).
     */
    private static Entity best(ClientPlayerEntity p, List<Entity> list, boolean bossStage) {
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : list) {
            if (blacklist.containsKey(e.getUuid())) continue;
            double score = p.distanceTo(e) + angleTo(p, aimPoint(e)) * 0.025;
            if (!bossStage) {
                int group = 0;
                for (Entity o : list) if (o != e && o.squaredDistanceTo(e) < 16) group++;
                score -= 1.0 * Math.min(group, 6);
                Integer lvl = levels.get(e);
                if (lvl != null && lvl > 0) score -= 0.6 * (Math.log(lvl + 1) / Math.log(2));
            }
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /** XP mobs of the chosen slayer inside the leash, not anyone else's boss or its minions. */
    private static List<Entity> xpMobs(MinecraftClient client) {
        Quest.Type type = SlayerBotClient.type;
        List<Vec3d> foreign = new ArrayList<>();
        String me = client.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        for (Entity e : client.world.getEntities()) {
            String tag = tagOf(e);
            if (tag.startsWith("spawned by") && !tag.contains(me)) foreign.add(pos(e));
        }
        // levels from the nametags floating above mobs ("[Lv12] Spider 160/160")
        levels.clear();
        List<Entity> tags = new ArrayList<>();
        for (Entity e : client.world.getEntities()) {
            if (e instanceof ArmorStandEntity && e.getCustomName() != null && tagOf(e).contains("lv")) tags.add(e);
        }
        List<Entity> out = new ArrayList<>();
        for (Entity e : client.world.getEntities()) {
            if (!type.mob.test(e) || !usable(client, e)) continue;
            if (!SlayerBotClient.inLeash(pos(e))) continue;
            if (Math.abs(e.getY() - client.player.getY()) > 10) continue;
            boolean theirs = false;
            for (Vec3d f : foreign) if (f.squaredDistanceTo(pos(e)) < 16) theirs = true;
            if (theirs) continue;
            out.add(e);
            for (Entity t : tags) {
                double fx = t.getX() - e.getX(), fz = t.getZ() - e.getZ(), dy = t.getY() - e.getY();
                if (fx * fx + fz * fz < 1.5 * 1.5 && dy > -0.5 && dy < 3.5) {
                    Matcher m = LEVEL.matcher(tagOf(t));
                    if (m.find()) levels.put(e, Integer.parseInt(m.group(1)));
                    break;
                }
            }
        }
        return out;
    }

    /** Your boss: the slayer mob standing under the "Spawned by: <you>" nametag. */
    private static List<Entity> ownBoss(MinecraftClient client) {
        List<Entity> out = new ArrayList<>();
        Entity tag = ownTag(client);
        if (tag == null) return out;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof MobEntity) || !usable(client, e) || !SlayerBotClient.type.mob.test(e)) continue;
            double flat = Math.sqrt(sq(e.getX() - tag.getX()) + sq(e.getZ() - tag.getZ()));
            double dy = tag.getY() - e.getY();
            if (flat > NAMETAG_RANGE || dy < -1 || dy > 5) continue;
            if (flat < bestD) {
                bestD = flat;
                best = e;
            }
        }
        if (best != null) out.add(best);
        return out;
    }

    static Entity ownTag(MinecraftClient client) {
        String me = client.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        for (Entity e : client.world.getEntities()) {
            String tag = tagOf(e);
            if (tag.startsWith("spawned by") && tag.contains(me)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ roaming

    /** Nothing in range: after a moment, walk to random spots inside the leash to find spawns. */
    private static void roam(MinecraftClient client) {
        idleTicks++;
        if (idleTicks < 30 || SlayerBotClient.leashCenter() == null) {   // give spawns 1.5 s first
            stopMoving(client);
            return;
        }
        if (roamGoal != null && walker != null) {
            Walker.Result r = walker.tick(client);
            if (r == Walker.Result.WALKING) return;
            walker.stop(client);
            walker = null;
            roamGoal = null;
            if (r == Walker.Result.FAILED) {
                idleTicks = 10;                         // try another spot shortly
                return;
            }
        }
        Vec3d c = SlayerBotClient.leashCenter();
        double ang = Math.random() * Math.PI * 2, rad = SlayerBotClient.leash * (0.3 + Math.random() * 0.55);
        roamGoal = new Vec3d(c.x + Math.cos(ang) * rad, c.y, c.z + Math.sin(ang) * rad);
        if (walker != null) walker.stop(client);
        stopKeys(client);
        walker = Walker.to(BlockPos.ofFloored(roamGoal), 3.0);
        state = State.PATHING;
    }

    /** How many of the slayer's mobs are within this many blocks of you (for the ability's group mode). */
    public static int nearby(MinecraftClient client, double range) {
        int n = 0;
        for (Entity e : client.world.getEntities()) {
            if (SlayerBotClient.type.mob.test(e) && usable(client, e) && client.player.distanceTo(e) <= range) n++;
        }
        return n;
    }

    private static String tagOf(Entity e) {
        if (!(e instanceof ArmorStandEntity) || e.getCustomName() == null) return "";
        String s = Formatting.strip(e.getCustomName().getString());
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean usable(MinecraftClient client, Entity e) {
        if (e == null || e.isRemoved() || e == client.player) return false;
        if (e instanceof LivingEntity le && (le.isDead() || le.getHealth() <= 0)) return false;
        return !e.isSpectator();
    }

    // ------------------------------------------------------------------ aiming

    private static Vec3d aimPoint(Entity e) {
        var b = e.getBoundingBox();
        return new Vec3d((b.minX + b.maxX) / 2, b.minY + (b.maxY - b.minY) * 0.65, (b.minZ + b.maxZ) / 2);
    }

    static float angleTo(ClientPlayerEntity p, Vec3d point) {
        Vec3d to = point.subtract(p.getEyePos()).normalize();
        double dot = MathHelper.clamp(p.getRotationVec(1f).dotProduct(to), -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(dot));
    }

    static Vec3d pos(Entity e) {
        return new Vec3d(e.getX(), e.getY(), e.getZ());
    }

    private static double flatDist(Vec3d a, Vec3d b) {
        return Math.sqrt(sq(a.x - b.x) + sq(a.z - b.z));
    }

    private static double sq(double v) {
        return v * v;
    }

    public static String describe(MinecraftClient client) {
        if (target == null) return "none";
        String n = target.getName().getString();
        return n + String.format(" %.1fm", client.player.distanceTo(target));
    }
}
