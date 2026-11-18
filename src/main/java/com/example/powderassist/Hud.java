package com.example.powderassist;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws on the HUD: the chest ESP (each chest's box projected onto the screen, so it shows
 * through walls), tracers, the solver's target marker and the powder tracker panel.
 */
public final class Hud {

    private Hud() {
    }

    private static final int CHEST_COLOR = 0xFFFFAA00, SOLVING_COLOR = 0xFF55FF55, TRACER_COLOR = 0xA0FFAA00;
    private static final double NEAR = 0.05;

    // camera basis for this frame
    private static Vec3d camPos, fwd, right, up;
    private static double tanHalf, aspect;
    private static int width, height;

    public static void render(DrawContext ctx, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        width = ctx.getScaledWindowWidth();
        height = ctx.getScaledWindowHeight();
        setupCamera(mc);

        if (PowderAssistClient.esp) {
            for (BlockPos c : Chests.treasure.keySet()) drawChest(ctx, mc, c);
        }
        Vec3d sp = Chests.solverPoint();
        if (sp != null) {
            double[] s = project(sp);
            if (s != null) {
                int x = (int) s[0], y = (int) s[1];
                ctx.fill(x - 3, y, x + 4, y + 1, SOLVING_COLOR);
                ctx.fill(x, y - 3, x + 1, y + 4, SOLVING_COLOR);
            }
        }
        if (PowderAssistClient.hud) drawPanel(ctx, mc.textRenderer);
    }

    // ------------------------------------------------------------------ projection

    private static void setupCamera(MinecraftClient mc) {
        Camera cam = mc.gameRenderer.getCamera();
        camPos = cam.getPos();
        fwd = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        right = fwd.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);   // looking straight up/down
        right = right.normalize();
        up = right.crossProduct(fwd).normalize();
        double fov = mc.options.getFov().getValue()
                * mc.player.getFovMultiplier(true, mc.options.getFovEffectScale().getValue().floatValue());
        tanHalf = Math.tan(Math.toRadians(fov) / 2.0);
        aspect = (double) width / height;
    }

    /** World point -> camera space (x right, y up, z forward). */
    private static Vec3d toView(Vec3d p) {
        Vec3d v = p.subtract(camPos);
        return new Vec3d(v.dotProduct(right), v.dotProduct(up), v.dotProduct(fwd));
    }

    private static double[] viewToScreen(Vec3d v) {
        double nx = v.x / (v.z * tanHalf * aspect), ny = v.y / (v.z * tanHalf);
        return new double[]{(nx + 1) / 2 * width, (1 - ny) / 2 * height};
    }

    /** World point -> screen (scaled GUI pixels), or null if it's behind you. */
    public static double[] project(Vec3d p) {
        Vec3d v = toView(p);
        if (v.z <= NEAR) return null;
        return viewToScreen(v);
    }

    /** Draws a world-space line, clipped at the camera so half-behind lines still show. */
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
        // keep it on (or near) the screen so a huge off-screen line doesn't cost thousands of dots
        double[] c = clipToScreen(x0, y0, x1, y1);
        if (c == null) return;
        x0 = c[0];
        y0 = c[1];
        x1 = c[2];
        y1 = c[3];
        int steps = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        if (steps == 0) {
            ctx.fill((int) x0, (int) y0, (int) x0 + 1, (int) y0 + 1, color);
            return;
        }
        steps = Math.min(steps, 3000);
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.floor(x0 + (x1 - x0) * i / steps), y = (int) Math.floor(y0 + (y1 - y0) * i / steps);
            ctx.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** Liang–Barsky clip of a 2D line to the screen; null if it's entirely off screen. */
    private static double[] clipToScreen(double x0, double y0, double x1, double y1) {
        double t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0, width - x0, y0, height - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, t);
                else t1 = Math.min(t1, t);
            }
        }
        if (t0 > t1) return null;
        return new double[]{x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy};
    }

    // ------------------------------------------------------------------ chest ESP

    private static void drawChest(DrawContext ctx, MinecraftClient mc, BlockPos c) {
        boolean solving = c.equals(Chests.solvingChest);
        int color = solving ? SOLVING_COLOR : CHEST_COLOR;
        double x0 = c.getX() + 0.0625, y0 = c.getY(), z0 = c.getZ() + 0.0625;
        double x1 = c.getX() + 0.9375, y1 = c.getY() + 0.875, z1 = c.getZ() + 0.9375;
        Vec3d[] v = {
                new Vec3d(x0, y0, z0), new Vec3d(x1, y0, z0), new Vec3d(x1, y0, z1), new Vec3d(x0, y0, z1),
                new Vec3d(x0, y1, z0), new Vec3d(x1, y1, z0), new Vec3d(x1, y1, z1), new Vec3d(x0, y1, z1)};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) line3d(ctx, v[e[0]], v[e[1]], color);

        Vec3d mid = Vec3d.ofCenter(c);
        if (PowderAssistClient.tracers && !solving) {
            Vec3d view = toView(mid);
            double cx = width / 2.0, cy = height / 2.0;
            if (view.z > NEAR) {
                double[] s = viewToScreen(view);
                line2d(ctx, cx, cy, s[0], s[1], TRACER_COLOR);
            } else {                                       // behind you: point toward it from the middle
                double len = Math.sqrt(view.x * view.x + view.y * view.y);
                if (len > 1e-6) {
                    double sx = view.x / len, sy = -view.y / len;
                    line2d(ctx, cx, cy, cx + sx * width, cy + sy * height, TRACER_COLOR);
                }
            }
        }
        double[] top = project(new Vec3d(mid.x, c.getY() + 1.3, mid.z));
        if (top != null) {
            double dist = mc.player.getEyePos().distanceTo(mid);
            String label = (solving ? "Solving " : "Chest ") + String.format("%.1fm", dist);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, label, (int) top[0], (int) top[1] - 4, color);
        }
    }

    // ------------------------------------------------------------------ tracker panel

    private static void drawPanel(DrawContext ctx, TextRenderer tr) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add("Powder Assist");
        colors.add(0xFF55FFFF);
        int[] typeColors = {0xFF55FF55, 0xFFFF55FF, 0xFF55FFFF};
        boolean shown = false;
        for (int i = 0; i < Tracker.TYPES.length; i++) {
            long g = Tracker.gained(i);
            if (g <= 0 && i == 2) continue;                // only show glacite once you get some
            lines.add(Tracker.TYPES[i] + ": +" + Tracker.shortNum(g) + "  (" + Tracker.shortNum(Tracker.perHour(g)) + "/h)");
            colors.add(typeColors[i]);
            shown = true;
        }
        if (!shown) {
            lines.add("No powder yet");
            colors.add(0xFFAAAAAA);
        }
        lines.add("Chests: " + Tracker.chestsOpened + " opened / " + Tracker.chestsFound + " found  ("
                + Tracker.perHour(Tracker.chestsOpened) + "/h)");
        colors.add(0xFFFFAA00);
        lines.add("Time: " + time(Tracker.sessionMs()) + "  (active " + time(Tracker.activeMs()) + ")");
        colors.add(0xFFDDDDDD);
        lines.add("Auto-mine " + (PowderAssistClient.autoMine ? "ON" : "OFF") + "   Solver "
                + (PowderAssistClient.solver ? (Chests.solving ? "SOLVING" : "ON") : "OFF"));
        colors.add(PowderAssistClient.autoMine || Chests.solving ? 0xFF55FF55 : 0xFFAAAAAA);

        int w = 0;
        for (String s : lines) w = Math.max(w, tr.getWidth(s));
        int h = lines.size() * 10 + 6;
        int corner = PowderAssistClient.hudCorner;
        int x = (corner == 1 || corner == 3) ? width - w - 10 : 4;
        int y = (corner == 2 || corner == 3) ? height - h - 4 : 4;
        ctx.fill(x, y, x + w + 6, y + h, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(tr, lines.get(i), x + 3, y + 4 + i * 10, colors.get(i));
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
