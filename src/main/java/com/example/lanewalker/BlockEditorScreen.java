package com.example.lanewalker;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Scratch-style block editor for Lane Walker scripts.
 *
 * Each script starts with a "when [key] pressed" hat block. Drag a new hat out
 * of the palette to add a script, and drag a hat back to the palette to delete
 * its script. Drag blocks into a script's "repeat" block, drag them around to
 * reorder them, and drag them back to the palette to delete them.
 */
public class BlockEditorScreen extends Screen {

    // Scratch colours
    private static final int MOTION = 0xFF4C97FF, MOTION_DARK = 0xFF3373CC;
    private static final int CONTROL = 0xFFFFAB19, CONTROL_DARK = 0xFFCF8B17;
    private static final int EVENTS = 0xFFFFBF00, EVENTS_DARK = 0xFFCC9900;
    private static final int GREEN = 0xFF59C059;
    private static final int PALETTE_BG = 0xFFE9EEF2, SCRIPT_BG = 0xFFF9F9F9, BORDER = 0xFFC6CCD8;
    private static final int WHITE = 0xFFFFFFFF, FIELD_TEXT = 0xFF575E75, HINT = 0xFF6B7290;
    private static final int CAPTURE_BG = 0xFFFFF3B0;

    private static final int BH = 22;      // stack block height
    private static final int ARM = 14;     // width of the repeat block's left arm
    private static final int TOP = 24;
    private static final int PAL_X = 8, PAL_W = 186;
    private static final int SCRIPT_GAP = 30;

    private enum HitType {
        PALETTE, HAT_TEMPLATE, HAT, KEY, RUN, BLOCK, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, BUTTON, MENU_ITEM
    }

    /** A clickable area; script = which script it belongs to (-1 = none), index = block index. */
    private record Hit(int x1, int y1, int x2, int y2, HitType type, int script, int index) {
        boolean contains(int x, int y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private enum SegType { LABEL, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, KEY, RUN }

    private record Seg(SegType type, String text) {}

    /** An open drop-down list. */
    private record Menu(HitType kind, int script, int index, int x, int y, int w, List<String> options, int selected) {}

    private static final List<String> DIR_OPTIONS = List.of("forward", "back", "left", "right");
    private static final List<String> WALK_MODE_OPTIONS = List.of("until wall", "blocks");
    private static final List<String> REPEAT_MODE_OPTIONS = List.of("forever", "times");
    private static int rememberedRepeats = 5;
    private static final int ITEM_H = 14;

    private static final Program.Step[] TEMPLATES = {
            new Program.Step(Program.Kind.WALK_WALL, Program.Dir.LEFT, 5),
            new Program.Step(Program.Kind.WALK_BLOCKS, Program.Dir.RIGHT, 5),
            new Program.Step(Program.Kind.SET_ANGLE, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.GO_TO, Program.Dir.LEFT, 0, 64, 0),
            new Program.Step(Program.Kind.WAIT, Program.Dir.LEFT, 1),
            new Program.Step(Program.Kind.WAIT_RANDOM, Program.Dir.LEFT, 5, 20),
            new Program.Step(Program.Kind.STOP_AT, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.IF, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.IF_AT, Program.Dir.LEFT, 0, 0),
    };
    private static final int MOTION_COUNT = 4;

    /** Keys handed out to new scripts, in order. */
    private static final int[] DEFAULT_KEYS = {
            GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_U,
            GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_M, GLFW.GLFW_KEY_O,
    };

    /** Every valid GLFW keyboard key code (asking GLFW about other codes logs errors). */
    private static final int[] VALID_KEYS = buildValidKeys();

    private static int[] buildValidKeys() {
        int[][] ranges = {{32, 32}, {39, 39}, {44, 57}, {59, 59}, {61, 61}, {65, 93}, {96, 96}, {161, 162},
                {256, 269}, {280, 284}, {290, 314}, {320, 336}, {340, 348}};
        List<Integer> keys = new ArrayList<>();
        for (int[] r : ranges) for (int k = r[0]; k <= r[1]; k++) keys.add(k);
        int[] out = new int[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = keys.get(i);
        return out;
    }

    private static final String[] BUTTONS = {"■ Stop", "Reset", "Done"};
    private static final int[] BUTTON_COLORS = {0xFFFF6680, 0xFF9AA0B4, MOTION};

    private List<Hit> hits = new ArrayList<>();
    private List<Hit> nextHits = new ArrayList<>();

    private boolean prevLeft, prevRight;

    // dragging
    private Program.Step dragStep = null;   // a block being dragged
    private boolean dragHatNew = false;     // a new "when key pressed" from the palette
    private int dragHatScript = -1;         // an existing script's hat (drop on the palette to delete)
    private int dragOffX, dragOffY;
    private int dropScript = -1, insertAt = -1;

    private int scrollY = 0, contentHeight = 0;
    private int palScroll = 0, palContentHeight = 0;
    private Menu menu = null;

    /** Id of the "if" block whose string is being connected, or -1. */
    private int linkFrom = -1;
    private int linkScript = -1;

    /** Script whose key is being picked ("press a key"), or -1. */
    private int capturing = -1;
    private final Set<Integer> captureHeld = new HashSet<>();

    /** Per frame: block rectangles {x1, y1, x2, color} and "if" sockets {x, y}, by block id. */
    private final Map<Integer, int[]> blockRects = new HashMap<>();
    private final Map<Integer, int[]> sockets = new HashMap<>();

    public BlockEditorScreen() {
        super(Text.literal("Lane Walker Blocks"));
    }

    private static Workspace ws() {
        return LaneWalkerClient.workspace;
    }

    private static Program script(int index) {
        List<Program> s = ws().scripts;
        return index >= 0 && index < s.size() ? s.get(index) : null;
    }

    @Override
    protected void init() {
        long h = client.getWindow().getHandle();
        prevLeft = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        prevRight = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return capturing < 0;   // while picking a key, Esc cancels instead of closing
    }

    @Override
    public void removed() {
        capturing = -1;
        LaneWalkerClient.saveProgram();
    }

    // ------------------------------------------------------------------ layout

    private int scriptX1() { return PAL_X + PAL_W + 8; }
    private int scriptX2() { return width - 8; }
    private int areaY2() { return height - 34; }

    private boolean inScript(int x, int y) {
        return x >= scriptX1() && x < scriptX2() && y >= TOP && y < areaY2();
    }

    private boolean inPalette(int x, int y) {
        return x >= PAL_X && x < PAL_X + PAL_W && y >= TOP && y < areaY2();
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
            switch (hit.type) {
                case MENU_ITEM -> { continue; }
                case PALETTE, HAT_TEMPLATE -> { if (!inPalette(mx, my)) continue; }
                case BUTTON -> { }
                default -> { if (!inScript(mx, my)) continue; }
            }
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

        if (capturing >= 0) {                // waiting for "press a key"
            if (leftDown || rightDown) capturing = -1;   // clicking anywhere cancels
            else pollCapture(h);
            return;
        }

        if (dragStep != null || dragHatNew || dragHatScript >= 0) {
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

        if (linkFrom >= 0) {                // connecting an "if": click a block in the same script
            Hit target = leftDown ? hitAt(mx, my) : null;
            Program p = script(linkScript);
            Program.Step from = p != null ? p.byId(linkFrom) : null;
            if (from != null && target != null && target.script == linkScript && target.index >= 0
                    && target.type != HitType.PALETTE && target.type != HitType.BUTTON) {
                Program.Step to = p.steps.get(target.index);
                if (to.id != from.id) from.link = to.id;
            }
            linkFrom = -1;
            linkScript = -1;
            return;
        }

        Hit hit = hitAt(mx, my);
        if (hit == null) return;
        int sign = leftDown ? 1 : -1;
        int step = shift() ? 10 : 1;
        Program p = script(hit.script);

        switch (hit.type) {
            case DIR -> openMenu(hit, DIR_OPTIONS, p.steps.get(hit.index).dir.ordinal());
            case MODE -> openMenu(hit, WALK_MODE_OPTIONS,
                    p.steps.get(hit.index).kind == Program.Kind.WALK_WALL ? 0 : 1);
            case RMODE -> openMenu(hit, REPEAT_MODE_OPTIONS, p.repeats == 0 ? 0 : 1);
            case LINK -> {
                Program.Step s = p.steps.get(hit.index);
                if (leftDown) {                  // start connecting
                    linkFrom = s.id;
                    linkScript = hit.script;
                } else {
                    s.link = -1;                 // right-click: cut the string
                }
            }
            case NUM -> changeAmount(p.steps.get(hit.index), sign * step);
            case NUM2 -> changeAmount2(p.steps.get(hit.index), sign * step);
            case NUM3 -> changeAmount3(p.steps.get(hit.index), sign * step);
            case REPEAT -> changeRepeats(p, sign * step);
            case KEY -> {
                if (leftDown) startCapture(hit.script, h);
                else p.key = -1;
            }
            case RUN -> {
                if (leftDown) {
                    LaneWalkerClient.saveProgram();
                    close();
                    LaneWalkerClient.start(client, p);
                }
            }
            case HAT -> {
                if (leftDown) {
                    dragHatScript = hit.script;
                    dragOffX = mx - hit.x1;
                    dragOffY = my - hit.y1;
                }
            }
            case HAT_TEMPLATE -> {
                if (leftDown && ws().scripts.size() < Workspace.MAX_SCRIPTS) {
                    dragHatNew = true;
                    dragOffX = mx - hit.x1;
                    dragOffY = my - hit.y1;
                }
            }
            case PALETTE -> {
                if (leftDown) {
                    Program.Step s = TEMPLATES[hit.index].copy();
                    if ((s.kind == Program.Kind.STOP_AT || s.kind == Program.Kind.IF_AT)
                            && client.player != null) {   // start at your spot
                        s.amount = MathHelper.floor(client.player.getX());
                        s.amount2 = MathHelper.floor(client.player.getZ());
                    }
                    if (s.kind == Program.Kind.GO_TO && client.player != null) {
                        s.amount = MathHelper.floor(client.player.getX());
                        s.amount2 = MathHelper.floor(client.player.getY());
                        s.amount3 = MathHelper.floor(client.player.getZ());
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
            case MENU_ITEM -> { }
        }
    }

    // ---- picking a key for a script

    private void startCapture(int scriptIndex, long handle) {
        capturing = scriptIndex;
        captureHeld.clear();
        for (int k : VALID_KEYS) if (GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS) captureHeld.add(k);
    }

    private void pollCapture(long handle) {
        for (int k : VALID_KEYS) {
            boolean down = GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS;
            if (!down) {
                captureHeld.remove(k);
                continue;
            }
            if (captureHeld.contains(k)) continue;
            Program p = script(capturing);
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                // cancel
            } else if (k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE) {
                if (p != null) p.key = -1;
            } else if (p != null) {
                for (Program other : ws().scripts) if (other != p && other.key == k) other.key = -1;   // no duplicates
                p.key = k;
            }
            capturing = -1;
            return;
        }
    }

    private static int freeKey() {
        for (int k : DEFAULT_KEYS) {
            boolean used = false;
            for (Program p : ws().scripts) if (p.key == k) used = true;
            if (!used) return k;
        }
        return -1;
    }

    // ---- drop-down menus

    private void openMenu(Hit hit, List<String> options, int selected) {
        int w = hit.x2 - hit.x1;
        for (String o : options) w = Math.max(w, textRenderer.getWidth("✔ " + o) + 12);
        menu = new Menu(hit.type, hit.script, hit.index, hit.x1, hit.y2 + 1, w, options, selected);
    }

    private void choose(Menu m, int option) {
        Program p = script(m.script);
        if (p == null) return;
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

    // ---- number fields

    private void changeAmount3(Program.Step s, int delta) {
        s.amount3 = Program.clamp(s.amount3 + delta, -Program.COORD_LIMIT, Program.COORD_LIMIT);
    }

    private void changeAmount(Program.Step s, int delta) {
        if (s.kind == Program.Kind.STOP_AT || s.kind == Program.Kind.IF_AT || s.kind == Program.Kind.GO_TO) {
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
        if (s.kind == Program.Kind.STOP_AT || s.kind == Program.Kind.IF_AT) {
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

    private void changeRepeats(Program p, int delta) {
        if (p.repeats == 0) return;                                 // forever: use the drop-down
        p.repeats = Program.clamp(p.repeats + delta, 1, 100000);
        rememberedRepeats = p.repeats;
    }

    // ---- dragging

    private void startDrag(Program.Step s, Hit hit, int mx, int my) {
        dragStep = s;
        dragOffX = mx - hit.x1;
        dragOffY = my - hit.y1;
    }

    private void drop(int mx, int my) {
        List<Program> scripts = ws().scripts;
        if (dragStep != null) {
            Program p = script(dropScript);
            if (inScript(mx, my) && p != null && insertAt >= 0 && p.steps.size() < Program.MAX_STEPS) {
                p.steps.add(Math.min(insertAt, p.steps.size()), dragStep);
            }
            // dropped anywhere else (e.g. back on the palette) = deleted
            dragStep = null;
        } else if (dragHatNew) {
            if (inScript(mx, my) && scripts.size() < Workspace.MAX_SCRIPTS) {
                Program np = new Program();
                np.key = freeKey();
                scripts.add(np);
            }
            dragHatNew = false;
        } else if (dragHatScript >= 0) {
            if (!inScript(mx, my) && dragHatScript < scripts.size()) scripts.remove(dragHatScript);   // delete script
            dragHatScript = -1;
        }
        dropScript = -1;
        insertAt = -1;
    }

    private void pressButton(int index) {
        switch (index) {
            case 0 -> LaneWalkerClient.stop(client, "stopped");
            case 1 -> {                       // Reset
                ws().scripts.clear();
                ws().scripts.addAll(Workspace.defaults().scripts);
                scrollY = 0;
            }
            case 2 -> close();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int mx = (int) mouseX, my = (int) mouseY;
        int dir = verticalAmount > 0 ? 1 : verticalAmount < 0 ? -1 : 0;
        if (dir == 0) return false;
        if (menu != null || capturing >= 0) return true;
        if (inPalette(mx, my)) {
            int max = Math.max(0, palContentHeight - (areaY2() - TOP) + 8);
            palScroll = Program.clamp(palScroll - dir * 16, 0, max);
            return true;
        }
        Hit hit = dragStep == null ? hitAt(mx, my) : null;
        Program p = hit != null ? script(hit.script) : null;
        int amount = dir * (shift() ? 10 : 1);
        if (p != null) {
            switch (hit.type) {
                case NUM -> { changeAmount(p.steps.get(hit.index), amount); return true; }
                case NUM2 -> { changeAmount2(p.steps.get(hit.index), amount); return true; }
                case NUM3 -> { changeAmount3(p.steps.get(hit.index), amount); return true; }
                case REPEAT -> { changeRepeats(p, amount); return true; }
                case DIR -> {
                    Program.Step s = p.steps.get(hit.index);
                    s.dir = dir > 0 ? s.dir.next() : s.dir.prev();
                    return true;
                }
                case MODE -> {
                    Program.Step s = p.steps.get(hit.index);
                    s.kind = s.kind == Program.Kind.WALK_WALL ? Program.Kind.WALK_BLOCKS : Program.Kind.WALK_WALL;
                    return true;
                }
                default -> { }
            }
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
        drawScripts(ctx, mouseX, mouseY);
        drawButtons(ctx, mouseX, mouseY);

        if (dragStep != null) {
            ctx.createNewRootLayer();
            drawStackBlock(ctx, mouseX - dragOffX, mouseY - dragOffY, dragStep, -1, -1, false, SCRIPT_BG);
        }
        if (dragHatNew || dragHatScript >= 0) {
            ctx.createNewRootLayer();
            drawHat(ctx, mouseX - dragOffX, mouseY - dragOffY, script(dragHatScript), -1, false);
        }
        if (menu != null) drawMenu(ctx, mouseX, mouseY);
        hits = nextHits;
    }

    private void drawPalette(DrawContext ctx) {
        int x1 = PAL_X, y1 = TOP, x2 = PAL_X + PAL_W, y2 = areaY2();
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, BORDER);
        ctx.fill(x1, y1, x2, y2, PALETTE_BG);
        ctx.enableScissor(x1, y1, x2, y2);

        int y = y1 + 6 - palScroll;
        ctx.drawText(textRenderer, "Events", x1 + 6, y, HINT, false);
        y += 12 + 7;                                     // room for the hat's hump
        int hw = drawHat(ctx, x1 + 6, y, null, -1, false);
        nextHits.add(new Hit(x1 + 6, y - 7, x1 + 6 + hw, y + BH, HitType.HAT_TEMPLATE, -1, -1));
        y += BH + 8;

        ctx.drawText(textRenderer, "Motion", x1 + 6, y, HINT, false);
        y += 12;
        for (int i = 0; i < TEMPLATES.length; i++) {
            if (i == MOTION_COUNT) {
                y += 3;
                ctx.drawText(textRenderer, "Control", x1 + 6, y, HINT, false);
                y += 12;
            }
            int w = drawStackBlock(ctx, x1 + 6, y, TEMPLATES[i], -1, -1, false, PALETTE_BG);
            nextHits.add(new Hit(x1 + 6, y, x1 + 6 + w, y + BH, HitType.PALETTE, -1, i));
            y += BH + 5;
        }
        y += 6;

        String[] tips = {
                "Drag \"when key pressed\"", "out to add a script.",
                "Click its key to change it,", "▶ runs that script.",
                "Drag blocks into the", "repeat block.",
                "Drag a block or hat back", "here to delete it.",
                "Click ▾ to open a list.", "Numbers: click = +1,", "right-click = -1,", "Shift = x10, or scroll.",
                "If: click its ●, then", "click a block to link it.", "Right-click ● to unlink.",
                "Scroll here for more.",
        };
        for (String tip : tips) {
            ctx.drawText(textRenderer, tip, x1 + 6, y, HINT, false);
            y += 10;
        }
        palContentHeight = y + palScroll - y1;
        ctx.disableScissor();
    }

    /** Draws a "when [key] pressed" hat block and returns its width. */
    private int drawHat(DrawContext ctx, int x, int y, Program p, int si, boolean editable) {
        String keyText = p == null ? "?" : capturing == si && si >= 0 ? "press a key" : LaneWalkerClient.keyName(p.key);
        List<Seg> segs = editable
                ? List.of(new Seg(SegType.LABEL, "when"), new Seg(SegType.KEY, keyText),
                new Seg(SegType.LABEL, "key pressed"), new Seg(SegType.RUN, "▶"))
                : List.of(new Seg(SegType.LABEL, "when"), new Seg(SegType.KEY, keyText),
                new Seg(SegType.LABEL, "key pressed"));
        int w = Math.max(130, segmentsWidth(segs) + 16);
        ctx.fill(x + 6, y - 7, x + 54, y - 5, EVENTS);
        ctx.fill(x + 2, y - 5, x + 62, y - 2, EVENTS);
        ctx.fill(x, y - 2, x + w, y + BH, EVENTS);
        ctx.fill(x, y + BH - 1, x + w, y + BH, EVENTS_DARK);
        if (editable) nextHits.add(0, new Hit(x, y - 7, x + w, y + BH, HitType.HAT, si, -1));
        drawSegments(ctx, x + 8, y, segs, si, -1, editable, EVENTS_DARK);
        return w;
    }

    private void drawScripts(DrawContext ctx, int mx, int my) {
        int x1 = scriptX1(), y1 = TOP, x2 = scriptX2(), y2 = areaY2();
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, BORDER);
        ctx.fill(x1, y1, x2, y2, SCRIPT_BG);
        ctx.enableScissor(x1, y1, x2, y2);

        List<Program> scripts = ws().scripts;
        int x = x1 + 14;
        int startY = y1 + 16 - scrollY;
        int y = startY;
        dropScript = -1;
        insertAt = -1;

        for (int si = 0; si < scripts.size(); si++) {
            Program p = scripts.get(si);
            drawHat(ctx, x, y, p, si, true);

            // repeat C-block
            int cy = y + BH;
            List<Seg> top = p.repeats == 0
                    ? List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.RMODE, "forever"))
                    : List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.REPEAT, String.valueOf(p.repeats)),
                    new Seg(SegType.RMODE, "times"));
            int cw = Math.max(150, segmentsWidth(top) + 16);
            ctx.fill(x, cy, x + cw, cy + BH, CONTROL);
            drawSegments(ctx, x + 8, cy, top, si, -1, true, CONTROL_DARK);
            ctx.fill(x + 9, cy, x + 19, cy + 2, EVENTS);    // tab from the hat block

            // would a dragged block land in this script?
            int innerTop = cy + BH;
            if (dragStep != null && dropScript < 0 && inScript(mx, my)) {
                int center = my - dragOffY + BH / 2;
                int lo = innerTop - BH, hi = innerTop + Math.max(1, p.steps.size()) * BH + BH;
                if (center >= lo && center < hi) {
                    dropScript = si;
                    insertAt = center < innerTop ? 0 : Program.clamp((center - innerTop) / BH, 0, p.steps.size());
                }
            }

            // inner blocks
            int iy = innerTop;
            List<int[]> tabs = new ArrayList<>();
            boolean preview = dropScript == si;
            int slots = p.steps.size() + (preview ? 1 : 0);
            for (int i = 0, s = 0; i < slots; i++) {
                if (preview && i == insertAt) {
                    ctx.fill(x + ARM, iy, x + ARM + 140, iy + BH, 0x40000000);   // drop preview
                } else {
                    Program.Step step = p.steps.get(s);
                    int w = drawStackBlock(ctx, x + ARM, iy, step, si, s, true, SCRIPT_BG);
                    nextHits.add(0, new Hit(x + ARM, iy, x + ARM + w, iy + BH, HitType.BLOCK, si, s));
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
            drawStrings(ctx, p, mx, my);

            y = innerBottom + 14 + SCRIPT_GAP;
        }
        if (scripts.isEmpty()) {
            ctx.drawText(textRenderer, "Drag a \"when key pressed\" block here to start a script.",
                    x, y, HINT, false);
            y += 20;
        }
        contentHeight = y - startY;
        ctx.disableScissor();
    }

    /** The coloured "strings" from each "if" socket in a script to the block it's linked to. */
    private void drawStrings(DrawContext ctx, Program p, int mx, int my) {
        int laneBase = 0;
        for (Program.Step s : p.steps) {
            int[] rect = blockRects.get(s.id);
            if (rect != null) laneBase = Math.max(laneBase, rect[2]);
        }
        laneBase += 8;
        int lane = 0;
        for (Program.Step s : p.steps) {
            if (s.kind != Program.Kind.IF || s.link < 0 || p.byId(s.link) == null) continue;
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
        if (linkFrom >= 0 && p.byId(linkFrom) != null && sockets.containsKey(linkFrom)) {   // string following the mouse
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
            nextHits.add(new Hit(m.x, iy, m.x + m.w, iy + ITEM_H, HitType.MENU_ITEM, m.script, i));
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
            nextHits.add(new Hit(bx, y, bx + bw, y + 20, HitType.BUTTON, -1, i));
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
                    new Seg(SegType.LABEL, "→ run next"), new Seg(SegType.LINK, ""));
            case IF_AT -> List.of(new Seg(SegType.LABEL, "if at x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)), new Seg(SegType.LABEL, "\u2192 run next"));
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
    private int drawStackBlock(DrawContext ctx, int x, int y, Program.Step s, int si, int index,
                               boolean editable, int bg) {
        boolean motion = !s.kind.isControl();
        int color = motion ? MOTION : CONTROL, dark = motion ? MOTION_DARK : CONTROL_DARK;
        List<Seg> segs = segmentsFor(s);
        int w = Math.max(120, segmentsWidth(segs) + 16);

        ctx.fill(x, y, x + w, y + BH, color);
        ctx.fill(x, y + BH - 1, x + w, y + BH, dark);
        ctx.fill(x + 8, y, x + 20, y + 2, bg);           // notch on top
        if (index < 0) ctx.fill(x + 9, y + BH, x + 19, y + BH + 2, color);   // tab (loose blocks)
        drawSegments(ctx, x + 8, y, segs, si, index, editable, dark);
        return w;
    }

    private int segWidth(Seg seg) {
        return switch (seg.type) {
            case LABEL -> textRenderer.getWidth(seg.text);
            case DIR, MODE, RMODE -> textRenderer.getWidth(seg.text + " ▾") + 10;
            case NUM, NUM2, NUM3, REPEAT, KEY -> Math.max(20, textRenderer.getWidth(seg.text) + 12);
            case LINK -> 14;
            case RUN -> 16;
        };
    }

    private int segmentsWidth(List<Seg> segs) {
        int w = 0;
        for (Seg s : segs) w += segWidth(s) + 5;
        return w - 5;
    }

    private void drawSegments(DrawContext ctx, int x, int y, List<Seg> segs, int si, int index,
                              boolean editable, int dark) {
        int tx = x, ty = y + (BH - 8) / 2;
        Program p = script(si);
        for (Seg seg : segs) {
            int w = segWidth(seg);
            int fy1 = y + 4, fy2 = y + BH - 4;
            switch (seg.type) {
                case LABEL -> ctx.drawText(textRenderer, seg.text, tx, ty, WHITE, false);
                case DIR, MODE, RMODE -> {
                    pill(ctx, tx, fy1, w, BH - 8, dark);
                    ctx.drawText(textRenderer, seg.text + " ▾", tx + 5, ty, WHITE, false);
                    HitType ht = seg.type == SegType.DIR ? HitType.DIR
                            : seg.type == SegType.MODE ? HitType.MODE : HitType.RMODE;
                    if (editable && p != null && (index >= 0 || ht == HitType.RMODE))
                        nextHits.add(new Hit(tx, fy1, tx + w, fy2, ht, si, index));
                }
                case LINK -> {
                    int cx = tx + 7, cyy = y + BH / 2;
                    Program.Step me = editable && p != null && index >= 0 ? p.steps.get(index) : null;
                    Program.Step linked = me != null && me.link >= 0 ? p.byId(me.link) : null;
                    int fillColor = linked != null ? colorOf(linked) : WHITE;
                    pill(ctx, cx - 6, cyy - 6, 12, 12, FIELD_TEXT);   // socket ring
                    pill(ctx, cx - 4, cyy - 4, 8, 8, fillColor);
                    if (me != null) {
                        sockets.put(me.id, new int[]{cx + 6, cyy});
                        nextHits.add(new Hit(cx - 7, cyy - 7, cx + 7, cyy + 7, HitType.LINK, si, index));
                    }
                }
                case RUN -> {
                    pill(ctx, tx, fy1, w, BH - 8, GREEN);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            WHITE, false);
                    if (editable && p != null) nextHits.add(new Hit(tx, fy1, tx + w, fy2, HitType.RUN, si, -1));
                }
                case NUM, NUM2, NUM3, REPEAT, KEY -> {
                    boolean picking = seg.type == SegType.KEY && capturing == si && si >= 0;
                    pill(ctx, tx, fy1, w, BH - 8, picking ? CAPTURE_BG : WHITE);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            FIELD_TEXT, false);
                    if (!editable || p == null) break;
                    HitType ht = switch (seg.type) {
                        case NUM -> HitType.NUM;
                        case NUM2 -> HitType.NUM2;
                        case NUM3 -> HitType.NUM3;
                        case REPEAT -> HitType.REPEAT;
                        default -> HitType.KEY;
                    };
                    boolean needsBlock = ht == HitType.NUM || ht == HitType.NUM2 || ht == HitType.NUM3;
                    if (!needsBlock || index >= 0) nextHits.add(new Hit(tx, fy1, tx + w, fy2, ht, si, index));
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
