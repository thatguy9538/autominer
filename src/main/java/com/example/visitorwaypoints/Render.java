package com.example.visitorwaypoints;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the waypoints on the HUD: each NPC spot's box is projected onto the screen (so it shows
 * through walls), with the visitor's name, what to do there and the distance.
 */
public final class Render {

    private Render() {
    }

    private static final double NEAR = 0.05;
    private static Vec3d camPos, fwd, right, up;
    private static double tanHalf, aspect;
    private static int width, height;

    public static void render(DrawContext ctx, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden || !VisitorWaypointsClient.enabled) return;
        List<VisitorWaypointsClient.Shown> shown = VisitorWaypointsClient.shown;
        width = ctx.getScaledWindowWidth();
        height = ctx.getScaledWindowHeight();
        setupCamera(mc);

        VisitorWaypointsClient.Shown nearest = null;
        double nearestD = Double.MAX_VALUE;
        for (VisitorWaypointsClient.Shown s : shown) {
            double d = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(s.pos());
            if (d < nearestD) {
                nearestD = d;
                nearest = s;
            }
        }
        for (VisitorWaypointsClient.Shown s : shown) draw(ctx, mc.textRenderer, mc, s, s == nearest);
        if (!shown.isEmpty()) {
            String head = shown.size() + " visitor waypoint" + (shown.size() == 1 ? "" : "s") + " on this island";
            ctx.drawTextWithShadow(mc.textRenderer, head, 4, 4, 0xFFFFDD55);
        }
    }

    private static int color(String rarity) {
        return switch (rarity) {
            case "UNCOMMON" -> 0xFF55FF55;
            case "RARE" -> 0xFF5555FF;
            case "LEGENDARY" -> 0xFFFFAA00;
            case "MYTHIC" -> 0xFFFF55FF;
            case "SPECIAL" -> 0xFFFF5555;
            default -> 0xFFFFFFFF;
        };
    }

    private static void draw(DrawContext ctx, TextRenderer tr, MinecraftClient mc, VisitorWaypointsClient.Shown s, boolean nearest) {
        int color = color(s.visitor().rarity());
        Vec3d p = s.pos();
        // an NPC-sized box at the spot
        double x0 = p.x - 0.3, x1 = p.x + 0.3, y0 = p.y, y1 = p.y + 1.9, z0 = p.z - 0.3, z1 = p.z + 0.3;
        Vec3d[] v = {
                new Vec3d(x0, y0, z0), new Vec3d(x1, y0, z0), new Vec3d(x1, y0, z1), new Vec3d(x0, y0, z1),
                new Vec3d(x0, y1, z0), new Vec3d(x1, y1, z0), new Vec3d(x1, y1, z1), new Vec3d(x0, y1, z1)};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) line3d(ctx, v[e[0]], v[e[1]], color);

        if (nearest && VisitorWaypointsClient.tracers) {
            Vec3d view = toView(new Vec3d(p.x, p.y + 1, p.z));
            double cx = width / 2.0, cy = height / 2.0;
            int tc = (color & 0x00FFFFFF) | 0x90000000;
            if (view.z > NEAR) {
                double[] sp = viewToScreen(view);
                line2d(ctx, cx, cy, sp[0], sp[1], tc);
            } else {
                double len = Math.sqrt(view.x * view.x + view.y * view.y);
                if (len > 1e-6) line2d(ctx, cx, cy, cx + view.x / len * width, cy - view.y / len * height, tc);
            }
        }

        double[] top = project(new Vec3d(p.x, p.y + 2.4, p.z));
        if (top == null) return;
        double dist = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(p);
        Visitors.Visitor vis = s.visitor();

        // name tag: name + rarity + distance, then "How to get them as a visitor", one step per line
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add(vis.name() + "  " + vis.rarity() + "  " + String.format("%.0fm", dist));
        colors.add(color);
        lines.add("How to get as a visitor:");
        colors.add(0xFFFFDD55);
        if (vis.gardenLevel() > 1) {
            int lvl = VisitorWaypointsClient.gardenLevel;
            boolean ok = VisitorWaypointsClient.levelOk(vis, Visitors.STATE.get(vis.name()));
            lines.add((ok ? "\u2714 " : "\u2716 ") + "Garden Level " + vis.gardenLevel()
                    + (lvl >= 0 ? "  (you: " + lvl + ")" : ""));
            colors.add(ok ? 0xFF55FF55 : 0xFFFF5555);
        }
        for (String step : vis.requirement().split(";")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            boolean note = step.toLowerCase(java.util.Locale.ROOT).startsWith("only visits");
            lines.add((note ? "" : "\u2022 ") + step);
            colors.add(note ? 0xFFAAAAAA : 0xFFFFFFFF);
        }
        lines.add(s.spotted() ? "spotted here" : "wiki spot: " + s.zone());
        colors.add(0xFF888888);

        int tx = (int) top[0], ty = (int) top[1];
        int w = 0;
        for (String l : lines) w = Math.max(w, tr.getWidth(l));
        w += 8;
        int h = lines.size() * 10 + 4;
        int y0 = ty - h;
        ctx.fill(tx - w / 2, y0, tx + w / 2, ty, 0xA0000000);
        ctx.fill(tx - w / 2, y0, tx + w / 2, y0 + 1, color);
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawCenteredTextWithShadow(tr, lines.get(i), tx, y0 + 3 + i * 10, colors.get(i));
        }
    }

    // ------------------------------------------------------------------ projection (screen-space, through walls)

    private static void setupCamera(MinecraftClient mc) {
        Camera cam = mc.gameRenderer.getCamera();
        camPos = cam.getPos();
        fwd = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        right = fwd.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);
        right = right.normalize();
        up = right.crossProduct(fwd).normalize();
        double fov = mc.options.getFov().getValue()
                * mc.player.getFovMultiplier(true, mc.options.getFovEffectScale().getValue().floatValue());
        tanHalf = Math.tan(Math.toRadians(fov) / 2.0);
        aspect = (double) width / height;
    }

    private static Vec3d toView(Vec3d p) {
        Vec3d v = p.subtract(camPos);
        return new Vec3d(v.dotProduct(right), v.dotProduct(up), v.dotProduct(fwd));
    }

    private static double[] viewToScreen(Vec3d v) {
        double nx = v.x / (v.z * tanHalf * aspect), ny = v.y / (v.z * tanHalf);
        return new double[]{(nx + 1) / 2 * width, (1 - ny) / 2 * height};
    }

    private static double[] project(Vec3d p) {
        Vec3d v = toView(p);
        if (v.z <= NEAR) return null;
        return viewToScreen(v);
    }

    private static void line3d(DrawContext ctx, Vec3d a, Vec3d b, int color) {
        Vec3d va = toView(a), vb = toView(b);
        if (va.z <= NEAR && vb.z <= NEAR) return;
        if (va.z <= NEAR) va = clip(vb, va);
        else if (vb.z <= NEAR) vb = clip(va, vb);
        double[] sa = viewToScreen(va), sb = viewToScreen(vb);
        line2d(ctx, sa[0], sa[1], sb[0], sb[1], color);
    }

    private static Vec3d clip(Vec3d in, Vec3d out) {
        double t = (in.z - NEAR) / (in.z - out.z);
        return in.add(out.subtract(in).multiply(t));
    }

    private static void line2d(DrawContext ctx, double x0, double y0, double x1, double y1, int color) {
        double t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0, width - x0, y0, height - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, t);
                else t1 = Math.min(t1, t);
            }
        }
        if (t0 > t1) return;
        double ax = x0 + t0 * dx, ay = y0 + t0 * dy, bx = x0 + t1 * dx, by = y0 + t1 * dy;
        int steps = (int) Math.min(3000, Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay))));
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.floor(ax + (bx - ax) * f), y = (int) Math.floor(ay + (by - ay) * f);
            ctx.fill(x, y, x + 1, y + 1, color);
        }
    }
}
