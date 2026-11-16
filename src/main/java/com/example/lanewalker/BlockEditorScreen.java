package com.example.lanewalker;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A Scratch-style block editor for the Lane Walker program.
 *
 * Drag blocks from the palette into the "repeat" block, drag them around to
 * reorder, and drag them back to the palette to delete them. Click a field to
 * change it: left-click = next / +1, right-click = previous / -1,
 * Shift = x10, mouse wheel also works.
 */
public class BlockEditorScreen extends Screen {

    // Scratch colours
    private static final int MOTION = 0xFF4C97FF, MOTION_DARK = 0xFF3373CC;
    private static final int CONTROL = 0xFFFFAB19, CONTROL_DARK = 0xFFCF8B17;
    private static final int EVENTS = 0xFFFFBF00, EVENTS_DARK = 0xFFCC9900;
    private static final int PALETTE_BG = 0xFFE9EEF2, SCRIPT_BG = 0xFFF9F9F9, BORDER = 0xFFC6CCD8;
    private static final int WHITE = 0xFFFFFFFF, FIELD_TEXT = 0xFF575E75, HINT = 0xFF6B7290;

    private static final int BH = 22;      // stack block height
    private static final int ARM = 14;     // width of the repeat block's left arm
    private static final int TOP = 24;
    private static final int PAL_X = 8, PAL_W = 176;

    private enum HitType { PALETTE, BLOCK, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, BUTTON, MENU_ITEM }

    private record Hit(int x1, int y1, int x2, int y2, HitType type, int index) {
        boolean contains(int x, int y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private enum SegType { LABEL, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE }

    /** An open drop-down list. */
    private record Menu(HitType kind, int index, int x, int y, int w, List<String> options, int selected) {}

    private static final List<String> DIR_OPTIONS = List.of("forward", "back", "left", "right");
    private static final List<String> WALK_MODE_OPTIONS = List.of("until wall", "blocks");
    private static final List<String> REPEAT_MODE_OPTIONS = List.of("forever", "times");
    private static int rememberedRepeats = 5;
    private static final int ITEM_H = 14;

    private record Seg(SegType type, String text) {}

    private static final Program.Step[] TEMPLATES = {
            new Program.Step(Program.Kind.WALK_WALL, Program.Dir.LEFT, 5),
            new Program.Step(Program.Kind.WALK_BLOCKS, Program.Dir.RIGHT, 5),
            new Program.Step(Program.Kind.SET_ANGLE, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.GO_TO, Program.Dir.LEFT, 0, 64, 0),
            new Program.Step(Program.Kind.WAIT, Program.Dir.LEFT, 1),
            new Program.Step(Program.Kind.WAIT_RANDOM, Program.Dir.LEFT, 5, 20),
            new Program.Step(Program.Kind.STOP_AT, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.IF, Program.Dir.LEFT, 0, 0),
    };

    private static final String[] BUTTONS = {"▶ Run", "■ Stop", "Reset", "Done"};
    private static final int[] BUTTON_COLORS = {0xFF59C059, 0xFFFF6680, 0xFF9AA0B4, MOTION};

    private List<Hit> hits = new ArrayList<>();
    private List<Hit> nextHits = new ArrayList<>();

    private boolean prevLeft, prevRight;
    private Program.Step dragStep = null;
    private int dragOffX, dragOffY;
    private int insertAt = -1;
    private int scrollY = 0;
    private int contentHeight = 0;
    private Menu menu = null;
    /** Id of the "if" block whose string is being connected, or -1. */
    private int linkFrom = -1;
    /** Per frame: block rectangles {x1, y1, x2, color} and "if" sockets {x, y}, by block id. */
    private final java.util.Map<Integer, int[]> blockRects = new java.util.HashMap<>();
    private final java.util.Map<Integer, int[]> sockets = new java.util.HashMap<>();

    public BlockEditorScreen() {
        super(Text.literal("Lane Walker Blocks"));
    }

    private Program program() {
        return LaneWalkerClient.program;
    }

    @Override
    protected void init() {
        long h = client.getWindow().getHandle();
        prevLeft = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        prevRight = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    @Override
    public void removed() {
        LaneWalkerClient.saveProgram();
    }

    // ------------------------------------------------------------------ layout

    private int scriptX1() { return PAL_X + PAL_W + 8; }
    private int scriptX2() { return width - 8; }
    private int areaY2() { return height - 34; }

    private boolean inScript(int x, int y) {
        return x >= scriptX1() && x < scriptX2() && y >= TOP && y < areaY2();
    }

    // ------------------------------------------------------------------ input

    private boolean shift() {
        long h = client.getWindow().getHandle();
        return GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private Hit hitAt(int mx, int my) {
        for (int i = hits.size() - 1; i >= 0; i--) {   // fields are recorded after their blocks
            Hit hit = hits.get(i);
            if (!hit.contains(mx, my)) continue;
            if (hit.type == HitType.MENU_ITEM) continue;
            boolean scriptHit = hit.type != HitType.PALETTE && hit.type != HitType.BUTTON;
            if (scriptHit && !inScript(mx, my)) continue;
            return hit;
        }
        return null;
    }

    private void handleInput(int mx, int my) {
        long h = client.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean leftDown = left && !prevLeft, rightDown = right && !prevRight, leftUp = !left && prevLeft;
        prevLeft = left;
        prevRight = right;

        if (dragStep != null) {
            if (leftUp) drop(mx, my);
            return;
        }
        if (!leftDown && !rightDown) return;

        if (menu != null) {                 // a drop-down is open: pick an option or close it
            Hit item = null;
            for (Hit h2 : hits) if (h2.type == HitType.MENU_ITEM && h2.contains(mx, my)) item = h2;
            if (item != null && leftDown) choose(menu, item.index);
            menu = null;
            return;
        }

        if (linkFrom >= 0) {               // connecting an "if": click the block to link to
            Hit target = leftDown ? hitAt(mx, my) : null;
            Program.Step from = program().byId(linkFrom);
            if (from != null && target != null && target.type != HitType.PALETTE && target.type != HitType.BUTTON
                    && target.index >= 0) {
                Program.Step to = program().steps.get(target.index);
                if (to.id != from.id) from.link = to.id;
            }
            linkFrom = -1;
            return;
        }

        Hit hit = hitAt(mx, my);
        if (hit == null) return;
        int sign = leftDown ? 1 : -1;
        int step = shift() ? 10 : 1;
        Program p = program();

        switch (hit.type) {
            case DIR -> openMenu(hit, DIR_OPTIONS, p.steps.get(hit.index).dir.ordinal());
            case MODE -> openMenu(hit, WALK_MODE_OPTIONS,
                    p.steps.get(hit.index).kind == Program.Kind.WALK_WALL ? 0 : 1);
            case RMODE -> openMenu(hit, REPEAT_MODE_OPTIONS, p.repeats == 0 ? 0 : 1);
            case MENU_ITEM -> { }
            case LINK -> {
                Program.Step s = p.steps.get(hit.index);
                if (leftDown) linkFrom = s.id;   // start connecting
                else s.link = -1;                // right-click: cut the string
            }
            case NUM -> changeAmount(p.steps.get(hit.index), sign * step);
            case NUM2 -> changeAmount2(p.steps.get(hit.index), sign * step);
            case NUM3 -> changeAmount3(p.steps.get(hit.index), sign * step);
            case REPEAT -> changeRepeats(sign * step);
            case PALETTE -> {
                if (leftDown) {
                    Program.Step s = TEMPLATES[hit.index].copy();
                    if (s.kind == Program.Kind.STOP_AT && client.player != null) {   // start at your spot
                        s.amount = net.minecraft.util.math.MathHelper.floor(client.player.getX());
                        s.amount2 = net.minecraft.util.math.MathHelper.floor(client.player.getZ());
                    }
                    if (s.kind == Program.Kind.GO_TO && client.player != null) {
                        s.amount = net.minecraft.util.math.MathHelper.floor(client.player.getX());
                        s.amount2 = net.minecraft.util.math.MathHelper.floor(client.player.getY());
                        s.amount3 = net.minecraft.util.math.MathHelper.floor(client.player.getZ());
                    }
                    startDrag(s, hit, mx, my);
                }
            }
            case BLOCK -> {
                if (leftDown) startDrag(p.steps.remove(hit.index), hit, mx, my);
            }
            case BUTTON -> {
                if (leftDown) pressButton(hit.index);
            }
        }
    }

    private void openMenu(Hit hit, List<String> options, int selected) {
        int w = hit.x2 - hit.x1;
        for (String o : options) w = Math.max(w, textRenderer.getWidth("✔ " + o) + 12);
        menu = new Menu(hit.type, hit.index, hit.x1, hit.y2 + 1, w, options, selected);
    }

    private void choose(Menu m, int option) {
        Program p = program();
        switch (m.kind) {
            case DIR -> p.steps.get(m.index).dir = Program.Dir.values()[option];
            case MODE -> p.steps.get(m.index).kind = option == 0 ? Program.Kind.WALK_WALL : Program.Kind.WALK_BLOCKS;
            case RMODE -> {
                if (option == 0) {
                    if (p.repeats > 0) rememberedRepeats = p.repeats;
                    p.repeats = 0;
                } else if (p.repeats == 0) {
                    p.repeats = Math.max(1, rememberedRepeats);
                }
            }
            default -> { }
        }
    }

    private void changeAmount3(Program.Step s, int delta) {
        s.amount3 = Program.clamp(s.amount3 + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
    }

    private void changeAmount(Program.Step s, int delta) {
        if (s.kind == Program.Kind.STOP_AT || s.kind == Program.Kind.GO_TO) {
            s.amount = Program.clamp(s.amount + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
        } else if (s.kind == Program.Kind.SET_ANGLE) {            // yaw wraps around
            int v = s.amount + delta;
            while (v > 180) v -= 360;
            while (v < -180) v += 360;
            s.amount = v;
        } else if (s.kind == Program.Kind.WAIT_RANDOM) {          // tenths of a second
            s.amount = Program.clamp(s.amount + delta, 0, Program.MAX_TENTHS);
            if (s.amount2 < s.amount) s.amount2 = s.amount;
        } else {
            s.amount = Program.clamp(s.amount + delta, 1, Program.MAX_AMOUNT);
        }
    }

    private void changeAmount2(Program.Step s, int delta) {
        if (s.kind == Program.Kind.GO_TO) {
            s.amount2 = Program.clamp(s.amount2 + delta, Program.MIN_Y, Program.MAX_Y);
            return;
        }
        if (s.kind == Program.Kind.STOP_AT) {
            s.amount2 = Program.clamp(s.amount2 + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
            return;
        }
        if (s.kind == Program.Kind.SET_ANGLE) {            // pitch: -90 up .. 90 down
            s.amount2 = Program.clamp(s.amount2 + delta, -90, 90);
            return;
        }
        s.amount2 = Program.clamp(s.amount2 + delta, 0, Program.MAX_TENTHS);
        if (s.amount > s.amount2) s.amount = s.amount2;
    }

    private void changeRepeats(int delta) {
        Program p = program();
        if (p.repeats == 0) return;                                 // forever: use the drop-down
        p.repeats = Program.clamp(p.repeats + delta, 1, 100000);
        rememberedRepeats = p.repeats;
    }

    private void startDrag(Program.Step s, Hit hit, int mx, int my) {
        dragStep = s;
        dragOffX = mx - hit.x1;
        dragOffY = my - hit.y1;
    }

    private void drop(int mx, int my) {
        Program p = program();
        if (inScript(mx, my) && insertAt >= 0 && p.steps.size() < Program.MAX_STEPS) {
            p.steps.add(Math.min(insertAt, p.steps.size()), dragStep);
        }
        // dropped anywhere else (e.g. back on the palette) = deleted
        dragStep = null;
        insertAt = -1;
    }

    private void pressButton(int index) {
        switch (index) {
            case 0 -> {                       // Run
                LaneWalkerClient.saveProgram();
                close();
                LaneWalkerClient.start(client);
            }
            case 1 -> LaneWalkerClient.stop(client, "stopped");
            case 2 -> {                       // Reset
                Program d = Program.defaults();
                program().repeats = d.repeats;
                program().steps.clear();
                program().steps.addAll(d.steps);
                scrollY = 0;
            }
            case 3 -> close();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int mx = (int) mouseX, my = (int) mouseY;
        int dir = verticalAmount > 0 ? 1 : verticalAmount < 0 ? -1 : 0;
        if (dir == 0) return false;
        if (menu != null) return true;
        Hit hit = dragStep == null ? hitAt(mx, my) : null;
        if (hit != null && hit.type == HitType.NUM) {
            changeAmount(program().steps.get(hit.index), dir * (shift() ? 10 : 1));
            return true;
        }
        if (hit != null && hit.type == HitType.NUM3) {
            changeAmount3(program().steps.get(hit.index), dir * (shift() ? 10 : 1));
            return true;
        }
        if (hit != null && hit.type == HitType.NUM2) {
            changeAmount2(program().steps.get(hit.index), dir * (shift() ? 10 : 1));
            return true;
        }
        if (hit != null && hit.type == HitType.REPEAT) {
            changeRepeats(dir * (shift() ? 10 : 1));
            return true;
        }
        if (hit != null && hit.type == HitType.DIR) {
            Program.Step s = program().steps.get(hit.index);
            s.dir = dir > 0 ? s.dir.next() : s.dir.prev();
            return true;
        }
        if (hit != null && hit.type == HitType.MODE) {
            Program.Step s = program().steps.get(hit.index);
            s.kind = s.kind == Program.Kind.WALK_WALL ? Program.Kind.WALK_BLOCKS : Program.Kind.WALK_WALL;
            return true;
        }
        if (inScript(mx, my)) {
            int max = Math.max(0, contentHeight - (areaY2() - TOP) + 16);
            scrollY = Program.clamp(scrollY - dir * 16, 0, max);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        handleInput(mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);   // dimmed / blurred background
        nextHits = new ArrayList<>();
        blockRects.clear();
        sockets.clear();

        ctx.drawCenteredTextWithShadow(textRenderer, "Lane Walker — Block Editor", width / 2, 8, WHITE);

        drawPalette(ctx);
        drawScript(ctx, mouseX, mouseY);
        drawButtons(ctx, mouseX, mouseY);

        if (dragStep != null) {
            ctx.createNewRootLayer();
            drawStackBlock(ctx, mouseX - dragOffX, mouseY - dragOffY, dragStep, -1, false, SCRIPT_BG);
        }
        if (menu != null) drawMenu(ctx, mouseX, mouseY);
        hits = nextHits;
    }

    private void drawPalette(DrawContext ctx) {
        int x1 = PAL_X, y1 = TOP, x2 = PAL_X + PAL_W, y2 = areaY2();
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, BORDER);
        ctx.fill(x1, y1, x2, y2, PALETTE_BG);

        int y = y1 + 6;
        ctx.drawText(textRenderer, "Motion", x1 + 6, y, HINT, false);
        y += 12;
        for (int i = 0; i < 4; i++) {
            int w = drawStackBlock(ctx, x1 + 6, y, TEMPLATES[i], -1, false, PALETTE_BG);
            nextHits.add(new Hit(x1 + 6, y, x1 + 6 + w, y + BH, HitType.PALETTE, i));
            y += BH + 5;
        }
        ctx.drawText(textRenderer, "Control", x1 + 6, y, HINT, false);
        y += 12;
        for (int i = 4; i < TEMPLATES.length; i++) {
            int w = drawStackBlock(ctx, x1 + 6, y, TEMPLATES[i], -1, false, PALETTE_BG);
            nextHits.add(new Hit(x1 + 6, y, x1 + 6 + w, y + BH, HitType.PALETTE, i));
            y += BH + 5;
        }
        y += 6;

        String[] tips = {
                "Drag blocks into the", "repeat block.",
                "Drag a block back here", "to delete it.",
                "Click \u25BE to open a list.", "Numbers: click = +1,", "right-click = -1,", "Shift = x10, or scroll.",
                "If: click its \u25CF, then", "click a block to link it.", "Right-click \u25CF to unlink.",
        };
        for (String tip : tips) {
            if (y + 9 > y2 - 4) break;
            ctx.drawText(textRenderer, tip, x1 + 6, y, HINT, false);
            y += 10;
        }
    }

    private void drawScript(DrawContext ctx, int mx, int my) {
        int x1 = scriptX1(), y1 = TOP, x2 = scriptX2(), y2 = areaY2();
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, BORDER);
        ctx.fill(x1, y1, x2, y2, SCRIPT_BG);
        ctx.enableScissor(x1, y1, x2, y2);

        Program p = program();
        int x = x1 + 14;
        int y = y1 + 16 - scrollY;

        // "when K pressed" hat block
        String key = LaneWalkerClient.runKeyName();
        List<Seg> hat = List.of(new Seg(SegType.LABEL, "when"), new Seg(SegType.NUM, key),
                new Seg(SegType.LABEL, "key pressed"));
        int hatW = Math.max(130, segmentsWidth(hat) + 16);
        ctx.fill(x + 6, y - 7, x + 54, y - 5, EVENTS);
        ctx.fill(x + 2, y - 5, x + 62, y - 2, EVENTS);
        ctx.fill(x, y - 2, x + hatW, y + BH, EVENTS);
        ctx.fill(x, y + BH - 1, x + hatW, y + BH, EVENTS_DARK);
        drawSegments(ctx, x + 8, y, hat, -1, false, EVENTS_DARK);
        int hatBottom = y + BH;

        // repeat C-block
        int cy = hatBottom;
        boolean forever = p.repeats == 0;
        List<Seg> top = forever
                ? List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.RMODE, "forever"))
                : List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.REPEAT, String.valueOf(p.repeats)),
                new Seg(SegType.RMODE, "times"));
        int cw = Math.max(150, segmentsWidth(top) + 16);
        ctx.fill(x, cy, x + cw, cy + BH, CONTROL);
        drawSegments(ctx, x + 8, cy, top, -1, true, CONTROL_DARK);
        ctx.fill(x + 9, cy, x + 19, cy + 2, EVENTS);    // tab from the hat block

        // where would a dragged block go?
        int innerTop = cy + BH;
        insertAt = -1;
        if (dragStep != null && inScript(mx, my)) {
            int rel = (my - dragOffY + BH / 2) - innerTop;
            insertAt = Program.clamp(rel / BH, 0, p.steps.size());
            if (rel < 0) insertAt = 0;
        }

        // inner blocks
        int iy = innerTop;
        List<int[]> tabs = new ArrayList<>();
        int slots = p.steps.size() + (insertAt >= 0 ? 1 : 0);
        for (int i = 0, s = 0; i < slots; i++) {
            if (i == insertAt) {
                ctx.fill(x + ARM, iy, x + ARM + 140, iy + BH, 0x40000000);   // drop preview
            } else {
                Program.Step step = p.steps.get(s);
                int w = drawStackBlock(ctx, x + ARM, iy, step, s, true, SCRIPT_BG);
                nextHits.add(0, new Hit(x + ARM, iy, x + ARM + w, iy + BH, HitType.BLOCK, s));
                tabs.add(new int[]{x + ARM, iy + BH, colorOf(step)});
                blockRects.put(step.id, new int[]{x + ARM, iy, x + ARM + w, colorOf(step)});
                s++;
            }
            iy += BH;
        }
        if (slots == 0) {
            ctx.drawText(textRenderer, "drag blocks in here", x + ARM + 6, iy + 7, HINT, false);
            iy += BH;
        }
        int innerBottom = iy;

        // arm + bottom of the C
        ctx.fill(x, innerTop, x + ARM, innerBottom, CONTROL);
        ctx.fill(x, innerBottom, x + cw, innerBottom + 14, CONTROL);
        ctx.fill(x, innerBottom + 13, x + cw, innerBottom + 14, CONTROL_DARK);
        ctx.fill(x + ARM + 9, innerTop, x + ARM + 19, innerTop + 2, CONTROL);   // tab into first block
        for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
        drawStrings(ctx, mx, my);

        contentHeight = innerBottom + 14 - (y1 + 16 - scrollY) + 16;
        ctx.disableScissor();
    }

    /** The coloured "strings" from each "if" socket to the block it's linked to. */
    private void drawStrings(DrawContext ctx, int mx, int my) {
        int laneBase = 0;
        for (int[] rect : blockRects.values()) laneBase = Math.max(laneBase, rect[2]);
        laneBase += 8;
        int lane = 0;
        for (Program.Step s : program().steps) {
            if (s.kind != Program.Kind.IF || s.link < 0) continue;
            int[] sock = sockets.get(s.id), target = blockRects.get(s.link);
            if (sock == null || target == null) continue;
            int lx = laneBase + lane * 5;
            lane++;
            int ty = target[1] + BH / 2, color = target[3];
            hLine(ctx, sock[0], lx, sock[1], color);
            vLine(ctx, lx, sock[1], ty, color);
            hLine(ctx, target[2], lx, ty, color);
            ctx.fill(target[2] - 2, ty - 2, target[2] + 1, ty + 3, color);   // knot on the target block
        }
        if (linkFrom >= 0 && sockets.containsKey(linkFrom)) {   // string following the mouse
            int[] sock = sockets.get(linkFrom);
            hLine(ctx, sock[0], mx, sock[1], FIELD_TEXT);
            vLine(ctx, mx, sock[1], my, FIELD_TEXT);
        }
    }

    private static void hLine(DrawContext ctx, int xa, int xb, int y, int color) {
        ctx.fill(Math.min(xa, xb), y - 1, Math.max(xa, xb) + 1, y + 1, color);
    }

    private static void vLine(DrawContext ctx, int x, int ya, int yb, int color) {
        ctx.fill(x - 1, Math.min(ya, yb), x + 1, Math.max(ya, yb) + 1, color);
    }

    private static int colorOf(Program.Step s) {
        return s.kind.isControl() ? CONTROL : MOTION;
    }

    private void drawMenu(DrawContext ctx, int mx, int my) {
        ctx.createNewRootLayer();
        Menu m = menu;
        int h = m.options.size() * ITEM_H + 4;
        int y = m.y;
        if (y + h > height - 2) y = Math.max(2, m.y - h - 18);   // flip up if it would go off screen
        int color = m.kind == HitType.RMODE ? CONTROL : MOTION;
        int dark = m.kind == HitType.RMODE ? CONTROL_DARK : MOTION_DARK;
        ctx.fill(m.x - 1, y - 1, m.x + m.w + 1, y + h + 1, dark);
        ctx.fill(m.x, y, m.x + m.w, y + h, color);
        for (int i = 0; i < m.options.size(); i++) {
            int iy = y + 2 + i * ITEM_H;
            boolean hover = mx >= m.x && mx < m.x + m.w && my >= iy && my < iy + ITEM_H;
            if (hover) ctx.fill(m.x + 1, iy, m.x + m.w - 1, iy + ITEM_H, dark);
            String label = (i == m.selected ? "✔ " : "    ") + m.options.get(i);
            ctx.drawText(textRenderer, label, m.x + 4, iy + 3, WHITE, false);
            nextHits.add(new Hit(m.x, iy, m.x + m.w, iy + ITEM_H, HitType.MENU_ITEM, i));
        }
    }

    private void drawButtons(DrawContext ctx, int mx, int my) {
        int bw = 64, gap = 8;
        int total = BUTTONS.length * bw + (BUTTONS.length - 1) * gap;
        int x = width / 2 - total / 2, y = height - 27;
        for (int i = 0; i < BUTTONS.length; i++) {
            int bx = x + i * (bw + gap);
            boolean hover = mx >= bx && mx < bx + bw && my >= y && my < y + 20;
            int c = BUTTON_COLORS[i];
            ctx.fill(bx + 1, y, bx + bw - 1, y + 20, c);
            ctx.fill(bx, y + 1, bx + bw, y + 19, c);
            ctx.fill(bx + 1, y + 19, bx + bw - 1, y + 20, darken(c));
            if (hover) ctx.fill(bx, y + 1, bx + bw, y + 19, 0x30FFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, BUTTONS[i], bx + bw / 2, y + 6, WHITE);
            nextHits.add(new Hit(bx, y, bx + bw, y + 20, HitType.BUTTON, i));
        }
    }

    // ------------------------------------------------------------------ blocks

    private List<Seg> segmentsFor(Program.Step s) {
        return switch (s.kind) {
            case WALK_WALL -> List.of(new Seg(SegType.LABEL, "walk"), new Seg(SegType.DIR, s.dir.label()),
                    new Seg(SegType.MODE, "until wall"));
            case WALK_BLOCKS -> List.of(new Seg(SegType.LABEL, "walk"), new Seg(SegType.DIR, s.dir.label()),
                    new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.MODE, "blocks"));
            case WAIT -> List.of(new Seg(SegType.LABEL, "wait"), new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.LABEL, s.amount == 1 ? "second" : "seconds"));
            case IF -> List.of(new Seg(SegType.LABEL, "if"), new Seg(SegType.LABEL, "done"),
                    new Seg(SegType.LABEL, "\u2192 run next"), new Seg(SegType.LINK, ""));
            case GO_TO -> List.of(new Seg(SegType.LABEL, "go to x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "y"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM3, String.valueOf(s.amount3)));
            case STOP_AT -> List.of(new Seg(SegType.LABEL, "stop at x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case SET_ANGLE -> List.of(new Seg(SegType.LABEL, "set angle x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "y"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
            case WAIT_RANDOM -> List.of(new Seg(SegType.LABEL, "random wait"),
                    new Seg(SegType.NUM, Program.tenths(s.amount)), new Seg(SegType.LABEL, "to"),
                    new Seg(SegType.NUM2, Program.tenths(s.amount2)), new Seg(SegType.LABEL, "s"));
        };
    }

    /** Draws a stack block and returns its width. */
    private int drawStackBlock(DrawContext ctx, int x, int y, Program.Step s, int index, boolean editable, int bg) {
        boolean motion = !s.kind.isControl();
        int color = motion ? MOTION : CONTROL, dark = motion ? MOTION_DARK : CONTROL_DARK;
        List<Seg> segs = segmentsFor(s);
        int w = Math.max(120, segmentsWidth(segs) + 16);

        ctx.fill(x, y, x + w, y + BH, color);
        ctx.fill(x, y + BH - 1, x + w, y + BH, dark);
        ctx.fill(x + 8, y, x + 20, y + 2, bg);           // notch on top
        if (index < 0) ctx.fill(x + 9, y + BH, x + 19, y + BH + 2, color);   // tab (loose blocks)
        drawSegments(ctx, x + 8, y, segs, index, editable, dark);
        return w;
    }

    private int segWidth(Seg seg) {
        return switch (seg.type) {
            case LABEL -> textRenderer.getWidth(seg.text);
            case DIR, MODE, RMODE -> textRenderer.getWidth(seg.text + " ▾") + 10;
            case NUM, NUM2, NUM3, REPEAT -> Math.max(20, textRenderer.getWidth(seg.text) + 12);
            case LINK -> 14;
        };
    }

    private int segmentsWidth(List<Seg> segs) {
        int w = 0;
        for (Seg s : segs) w += segWidth(s) + 5;
        return w - 5;
    }

    private void drawSegments(DrawContext ctx, int x, int y, List<Seg> segs, int index, boolean editable, int dark) {
        int tx = x, ty = y + (BH - 8) / 2;
        for (Seg seg : segs) {
            int w = segWidth(seg);
            switch (seg.type) {
                case LABEL -> ctx.drawText(textRenderer, seg.text, tx, ty, WHITE, false);
                case DIR, MODE, RMODE -> {
                    pill(ctx, tx, y + 4, w, BH - 8, dark);
                    ctx.drawText(textRenderer, seg.text + " ▾", tx + 5, ty, WHITE, false);
                    HitType ht = seg.type == SegType.DIR ? HitType.DIR
                            : seg.type == SegType.MODE ? HitType.MODE : HitType.RMODE;
                    if (editable && (index >= 0 || ht == HitType.RMODE))
                        nextHits.add(new Hit(tx, y + 4, tx + w, y + BH - 4, ht, index));
                }
                case LINK -> {
                    int cx = tx + 7, cyy = y + BH / 2;
                    Program.Step me = editable && index >= 0 ? program().steps.get(index) : null;
                    Program.Step linked = me != null && me.link >= 0 ? program().byId(me.link) : null;
                    int fillColor = linked != null ? colorOf(linked) : WHITE;
                    pill(ctx, cx - 6, cyy - 6, 12, 12, FIELD_TEXT);   // socket ring
                    pill(ctx, cx - 4, cyy - 4, 8, 8, fillColor);
                    if (me != null) {
                        sockets.put(me.id, new int[]{cx + 6, cyy});
                        nextHits.add(new Hit(cx - 7, cyy - 7, cx + 7, cyy + 7, HitType.LINK, index));
                    }
                }
                case NUM, NUM2, NUM3, REPEAT -> {
                    pill(ctx, tx, y + 4, w, BH - 8, WHITE);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            FIELD_TEXT, false);
                    if (editable && seg.type == SegType.NUM && index >= 0)
                        nextHits.add(new Hit(tx, y + 4, tx + w, y + BH - 4, HitType.NUM, index));
                    if (editable && seg.type == SegType.NUM3 && index >= 0)
                        nextHits.add(new Hit(tx, y + 4, tx + w, y + BH - 4, HitType.NUM3, index));
                    if (editable && seg.type == SegType.NUM2 && index >= 0)
                        nextHits.add(new Hit(tx, y + 4, tx + w, y + BH - 4, HitType.NUM2, index));
                    if (editable && seg.type == SegType.REPEAT)
                        nextHits.add(new Hit(tx, y + 4, tx + w, y + BH - 4, HitType.REPEAT, -1));
                }
            }
            tx += w + 5;
        }
    }

    /** A rounded-looking field (rectangle with clipped corners). */
    private static void pill(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 2, y, x + w - 2, y + h, color);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        ctx.fill(x, y + 2, x + w, y + h - 2, color);
    }

    private static int darken(int c) {
        int r = ((c >> 16) & 0xFF) * 4 / 5, g = ((c >> 8) & 0xFF) * 4 / 5, b = (c & 0xFF) * 4 / 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
