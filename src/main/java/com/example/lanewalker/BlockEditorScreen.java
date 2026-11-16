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
 * Each script starts with a "when [key] pressed" hat block. Blocks snap under
 * it, and "C" blocks (repeat, if at, if done) hold their own section of blocks.
 * Drag a hat or block back to the palette to delete it.
 */
public class BlockEditorScreen extends Screen {

    // Scratch colours
    private static final int MOTION = 0xFF4C97FF, MOTION_DARK = 0xFF3373CC;
    private static final int CONTROL = 0xFFFFAB19, CONTROL_DARK = 0xFFCF8B17;
    private static final int EVENTS = 0xFFFFBF00, EVENTS_DARK = 0xFFCC9900;
    private static final int GREEN = 0xFF59C059;
    private static final int SENSING = 0xFF5CB1D6, SENSING_DARK = 0xFF2E8EB8;
    private static final int PALETTE_BG = 0xFFE9EEF2, SCRIPT_BG = 0xFFF9F9F9, BORDER = 0xFFC6CCD8;
    private static final int WHITE = 0xFFFFFFFF, FIELD_TEXT = 0xFF575E75, HINT = 0xFF6B7290;
    private static final int CAPTURE_BG = 0xFFFFF3B0, DROP_LINE = 0xFF575E75;

    private static final int BH = 22;      // stack block height
    private static final int ARM = 14;     // width of a C block's left arm
    private static final int C_BOTTOM = 12;
    private static final int EMPTY_C = 16; // height of an empty C block's inside
    private static final int TOP = 24;
    private static final int PAL_X = 8, PAL_W = 176;
    private static final int SCRIPT_GAP = 30;

    private enum HitType {
        PALETTE, HAT_TEMPLATE, HAT, KEY, HOLDKEY, RUN, BLOCK, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, BUTTON, MENU_ITEM
    }

    /**
     * A clickable area. script = which script (-1 = none); step = the block it belongs to;
     * list = the list that block sits in; index = palette / button / menu item number.
     */
    private record Hit(int x1, int y1, int x2, int y2, HitType type, int script,
                       Program.Step step, List<Program.Step> list, int index) {
        boolean contains(int x, int y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    /** A place a dragged block could be dropped: position index in a list. */
    private record DropPoint(List<Program.Step> list, int index, int x, int y) {}

    private enum SegType { LABEL, DIR, MODE, NUM, NUM2, NUM3, LINK, REPEAT, RMODE, KEY, HOLDKEY, RUN }

    private record Seg(SegType type, String text) {}

    /** An open drop-down list. */
    private record Menu(HitType kind, Program.Step step, int x, int y, int w, List<String> options, int selected) {}

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
            new Program.Step(Program.Kind.REPEAT, Program.Dir.LEFT, 0),
            new Program.Step(Program.Kind.IF_AT, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.IF, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.WAIT, Program.Dir.LEFT, 1),
            new Program.Step(Program.Kind.WAIT_RANDOM, Program.Dir.LEFT, 5, 20),
            new Program.Step(Program.Kind.STOP_AT, Program.Dir.LEFT, 0, 0),
            new Program.Step(Program.Kind.HOLD_KEY, Program.Dir.LEFT, 0, 10, 1),   // hold left click for 1.0 s
    };
    private static final int MOTION_COUNT = 4;
    private static final int KEYS_START = 10;

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
    private final List<DropPoint> dropPoints = new ArrayList<>();
    private DropPoint bestDrop = null;

    private boolean prevLeft, prevRight;

    // dragging
    private Program.Step dragStep = null;   // a block (with anything inside it) being dragged
    private boolean dragHatNew = false;     // a new "when key pressed" from the palette
    private int dragHatScript = -1;         // an existing script's hat (drop on the palette to delete)
    private int dragOffX, dragOffY;

    private int scrollY = 0, contentHeight = 0;
    private int palScroll = 0, palContentHeight = 0;
    private Menu menu = null;

    /** The "if done" block whose string is being connected, and its script. */
    private Program.Step linkFrom = null;
    private int linkScript = -1;

    /** Script whose key is being picked ("press a key"), or -1. */
    private int capturing = -1;
    /** "hold key" block whose key is being picked, or null. */
    private Program.Step capturingStep = null;
    private final Set<Integer> captureHeld = new HashSet<>();

    /** Per frame: block rectangles {x1, y1, x2, color} and "if" sockets {x, y}, by block id. */
    private final Map<Integer, int[]> blockRects = new HashMap<>();
    private final Map<Integer, int[]> sockets = new HashMap<>();
    /** Per frame: little connector tabs {x, y, color}, drawn after the blocks. */
    private final List<int[]> tabs = new ArrayList<>();

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
        return capturing < 0 && capturingStep == null;   // while picking a key, Esc cancels instead of closing
    }

    @Override
    public void removed() {
        capturing = -1;
        capturingStep = null;
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
        if (capturingStep != null) {         // "hold key" block: a key OR a mouse button
            boolean middleDown = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            if (leftDown || rightDown || middleDown) {
                capturingStep.amount = leftDown ? 0 : rightDown ? 1 : 2;
                capturingStep.amount3 = 1;
                capturingStep = null;
            } else {
                pollCapture(h);
            }
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

        if (linkFrom != null) {             // connecting an "if done": click a block in the same script
            Hit target = leftDown ? hitAt(mx, my) : null;
            if (target != null && target.step != null && target.script == linkScript && target.step != linkFrom) {
                linkFrom.link = target.step.id;
            }
            linkFrom = null;
            linkScript = -1;
            return;
        }

        Hit hit = hitAt(mx, my);
        if (hit == null) return;
        int sign = leftDown ? 1 : -1;
        int step = shift() ? 10 : 1;
        Program p = script(hit.script);
        Program.Step s = hit.step;

        switch (hit.type) {
            case DIR -> openMenu(hit, DIR_OPTIONS, s.dir.ordinal());
            case MODE -> openMenu(hit, WALK_MODE_OPTIONS, s.kind == Program.Kind.WALK_WALL ? 0 : 1);
            case RMODE -> openMenu(hit, REPEAT_MODE_OPTIONS, s.amount == 0 ? 0 : 1);
            case LINK -> {
                if (leftDown) {                  // start connecting
                    linkFrom = s;
                    linkScript = hit.script;
                } else {
                    s.link = -1;                 // right-click: cut the string
                }
            }
            case NUM -> changeAmount(s, sign * step);
            case NUM2 -> changeAmount2(s, sign * step);
            case NUM3 -> changeAmount3(s, sign * step);
            case REPEAT -> changeRepeats(s, sign * step);
            case KEY -> {
                if (leftDown) startCapture(hit.script, h);
                else if (p != null) p.key = -1;
            }
            case HOLDKEY -> {
                if (leftDown) {
                    startCapture(-1, h);
                    capturingStep = s;
                }
            }
            case RUN -> {
                if (leftDown && p != null) {
                    LaneWalkerClient.saveProgram();
                    close();
                    LaneWalkerClient.start(client, p);
                }
            }
            case HAT -> {
                if (leftDown) {
                    dragHatScript = hit.script;
                    dragOffX = mx - hit.x1;
                    dragOffY = my - hit.y1 - 7;
                }
            }
            case HAT_TEMPLATE -> {
                if (leftDown && ws().scripts.size() < Workspace.MAX_SCRIPTS) {
                    dragHatNew = true;
                    dragOffX = mx - hit.x1;
                    dragOffY = my - hit.y1 - 7;
                }
            }
            case PALETTE -> {
                if (leftDown) {
                    Program.Step n = TEMPLATES[hit.index].copy();
                    if ((n.kind == Program.Kind.STOP_AT || n.kind == Program.Kind.IF_AT) && client.player != null) {
                        n.amount = MathHelper.floor(client.player.getX());   // start at your spot
                        n.amount2 = MathHelper.floor(client.player.getZ());
                    }
                    if (n.kind == Program.Kind.GO_TO && client.player != null) {
                        n.amount = MathHelper.floor(client.player.getX());
                        n.amount2 = MathHelper.floor(client.player.getY());
                        n.amount3 = MathHelper.floor(client.player.getZ());
                    }
                    startDrag(n, hit, mx, my);
                }
            }
            case BLOCK -> {
                if (leftDown && hit.list != null) {
                    hit.list.remove(s);          // takes anything inside it along too
                    startDrag(s, hit, mx, my);
                }
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
            if (capturingStep != null) {
                if (k != GLFW.GLFW_KEY_ESCAPE) {
                    capturingStep.amount = k;
                    capturingStep.amount3 = 0;
                }
                capturingStep = null;
                return;
            }
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
        menu = new Menu(hit.type, hit.step, hit.x1, hit.y2 + 1, w, options, selected);
    }

    private void choose(Menu m, int option) {
        Program.Step s = m.step;
        if (s == null) return;
        switch (m.kind) {
            case DIR -> s.dir = Program.Dir.values()[option];
            case MODE -> s.kind = option == 0 ? Program.Kind.WALK_WALL : Program.Kind.WALK_BLOCKS;
            case RMODE -> {
                if (option == 0) {
                    if (s.amount > 0) rememberedRepeats = s.amount;
                    s.amount = 0;
                } else if (s.amount == 0) {
                    s.amount = Math.max(1, rememberedRepeats);
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
        if (s.kind == Program.Kind.HOLD_KEY) {              // tenths of a second, at least 0.1
            s.amount2 = Program.clamp(s.amount2 + delta, 1, Program.MAX_TENTHS);
            return;
        }
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

    private void changeRepeats(Program.Step s, int delta) {
        if (s.amount == 0) return;                                 // forever: use the drop-down
        s.amount = Program.clamp(s.amount + delta, 1, 100000);
        rememberedRepeats = s.amount;
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
            DropPoint d = bestDrop;
            if (inScript(mx, my) && d != null && d.list.size() < Program.MAX_STEPS) {
                d.list.add(Math.min(d.index, d.list.size()), dragStep);
            }
            // dropped anywhere else (e.g. back on the palette) = deleted
            dragStep = null;
        } else if (dragHatNew) {
            if (inScript(mx, my) && scripts.size() < Workspace.MAX_SCRIPTS) {
                Program np = new Program();      // new scripts start empty: just the hat
                np.key = freeKey();
                scripts.add(np);
            }
            dragHatNew = false;
        } else if (dragHatScript >= 0) {
            if (!inScript(mx, my) && dragHatScript < scripts.size()) scripts.remove(dragHatScript);   // delete script
            dragHatScript = -1;
        }
        bestDrop = null;
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
        if (menu != null || capturing >= 0 || capturingStep != null) return true;
        if (inPalette(mx, my)) {
            int max = Math.max(0, palContentHeight - (areaY2() - TOP) + 8);
            palScroll = Program.clamp(palScroll - dir * 16, 0, max);
            return true;
        }
        Hit hit = dragStep == null ? hitAt(mx, my) : null;
        Program.Step s = hit != null ? hit.step : null;
        int amount = dir * (shift() ? 10 : 1);
        if (s != null) {
            switch (hit.type) {
                case NUM -> { changeAmount(s, amount); return true; }
                case NUM2 -> { changeAmount2(s, amount); return true; }
                case NUM3 -> { changeAmount3(s, amount); return true; }
                case REPEAT -> { changeRepeats(s, amount); return true; }
                case DIR -> {
                    s.dir = dir > 0 ? s.dir.next() : s.dir.prev();
                    return true;
                }
                case MODE -> {
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
        dropPoints.clear();

        ctx.drawCenteredTextWithShadow(textRenderer, "Lane Walker — Block Editor", width / 2, 8, WHITE);

        drawPalette(ctx);
        drawScripts(ctx, mouseX, mouseY);
        drawButtons(ctx, mouseX, mouseY);

        if (dragStep != null) {
            ctx.createNewRootLayer();
            tabs.clear();
            drawBlock(ctx, dragStep, null, mouseX - dragOffX, mouseY - dragOffY, -1, false, SCRIPT_BG, null);
            for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
            tabs.clear();
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
        nextHits.add(new Hit(x1 + 6, y - 7, x1 + 6 + hw, y + BH, HitType.HAT_TEMPLATE, -1, null, null, -1));
        y += BH + 8;

        ctx.drawText(textRenderer, "Motion", x1 + 6, y, HINT, false);
        y += 12;
        tabs.clear();
        for (int i = 0; i < TEMPLATES.length; i++) {
            if (i == MOTION_COUNT || i == KEYS_START) {
                y += 3;
                ctx.drawText(textRenderer, i == MOTION_COUNT ? "Control" : "Keys", x1 + 6, y, HINT, false);
                y += 12;
            }
            int[] size = new int[1];
            int bottom = drawBlock(ctx, TEMPLATES[i], null, x1 + 6, y, -1, false, PALETTE_BG, size);
            nextHits.add(new Hit(x1 + 6, y, x1 + 6 + size[0], bottom, HitType.PALETTE, -1, null, null, i));
            y = bottom + 5;
        }
        for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
        tabs.clear();
        y += 6;

        String[] tips = {
                "Drag \"when key pressed\"", "out to add a script.",
                "Click its key to change it,", "▶ runs that script.",
                "Blocks snap under a hat", "or inside repeat / if.",
                "Drag a block or hat back", "here to delete it.",
                "Click ▾ to open a list.", "Numbers: click = +1,", "right-click = -1,", "Shift = x10, or scroll.",
                "If done: click its ●,", "then a block to tie it.", "Right-click ● to untie.",
                "Hold key: click its key box,", "then press a key or click", "a mouse button.",
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
        if (editable) nextHits.add(0, new Hit(x, y - 7, x + w, y + BH, HitType.HAT, si, null, null, -1));
        drawSegments(ctx, x + 8, y, segs, si, null, editable, EVENTS_DARK);
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

        for (int si = 0; si < scripts.size(); si++) {
            Program p = scripts.get(si);
            tabs.clear();
            drawHat(ctx, x, y, p, si, true);
            int listTop = y + BH;
            if (!p.steps.isEmpty()) tabs.add(new int[]{x, listTop, EVENTS});
            int bottom = drawList(ctx, p.steps, x, listTop, si, true, SCRIPT_BG);
            if (p.steps.isEmpty()) {
                ctx.drawText(textRenderer, "drag blocks under here", x + 8, listTop + 6, HINT, false);
                bottom = listTop + BH;
            }
            for (int[] t : tabs) ctx.fill(t[0] + 9, t[1], t[0] + 19, t[1] + 2, t[2]);
            tabs.clear();
            drawStrings(ctx, p, mx, my);
            y = bottom + SCRIPT_GAP;
        }
        if (scripts.isEmpty()) {
            ctx.drawText(textRenderer, "Drag a \"when key pressed\" block here to start a script.",
                    x, y, HINT, false);
            y += 20;
        }
        contentHeight = y - startY;

        // where would the dragged block land? show a line there
        bestDrop = null;
        if (dragStep != null && inScript(mx, my)) {
            int bx = mx - dragOffX, by = my - dragOffY;
            int best = Integer.MAX_VALUE;
            for (DropPoint d : dropPoints) {
                int dx = Math.abs(bx - d.x), dy = Math.abs(by - d.y);
                if (dx > 70 || dy > 18) continue;
                int score = dy * 3 + dx;
                if (score < best) {
                    best = score;
                    bestDrop = d;
                }
            }
            if (bestDrop != null) ctx.fill(bestDrop.x, bestDrop.y - 2, bestDrop.x + 120, bestDrop.y + 2, DROP_LINE);
        }
        ctx.disableScissor();
    }

    /** Draws a list of blocks stacked from (x, y) and returns the y under the last one. */
    private int drawList(DrawContext ctx, List<Program.Step> list, int x, int y, int si, boolean live, int bg) {
        for (int i = 0; i <= list.size(); i++) {
            if (live) dropPoints.add(new DropPoint(list, i, x, y));
            if (i == list.size()) break;
            y = drawBlock(ctx, list.get(i), list, x, y, si, live, bg, null);
        }
        return y;
    }

    /**
     * Draws one block (and, for repeat / if, everything inside it). Returns the y under it.
     * live = it's part of a script (clickable, droppable); size[0] gets its width if given.
     */
    private int drawBlock(DrawContext ctx, Program.Step s, List<Program.Step> list, int x, int y, int si,
                          boolean live, int bg, int[] size) {
        int color = colorOf(s), dark = darkOf(s);
        List<Seg> segs = segmentsFor(s);

        if (!s.kind.isContainer()) {
            int w = Math.max(120, segmentsWidth(segs) + 16);
            ctx.fill(x, y, x + w, y + BH, color);
            ctx.fill(x, y + BH - 1, x + w, y + BH, dark);
            ctx.fill(x + 8, y, x + 20, y + 2, bg);           // notch on top
            tabs.add(new int[]{x, y + BH, color});            // tab underneath
            if (live) {
                nextHits.add(0, new Hit(x, y, x + w, y + BH, HitType.BLOCK, si, s, list, -1));
                blockRects.put(s.id, new int[]{x, y, x + w, color});
            }
            drawSegments(ctx, x + 8, y, segs, si, s, live, dark);
            if (size != null) size[0] = w;
            return y + BH;
        }

        // a C block: top bar, the blocks inside, then the bottom bar
        int w = Math.max(150, segmentsWidth(segs) + 16);
        ctx.fill(x, y, x + w, y + BH, color);
        ctx.fill(x + 8, y, x + 20, y + 2, bg);               // notch on top
        int innerTop = y + BH;
        if (!s.children.isEmpty()) tabs.add(new int[]{x + ARM, innerTop, color});   // tab into the first block
        int innerBottom = drawList(ctx, s.children, x + ARM, innerTop, si, live, bg);
        if (s.children.isEmpty()) innerBottom = innerTop + EMPTY_C;
        ctx.fill(x, innerTop, x + ARM, innerBottom, color);                      // left arm
        ctx.fill(x, innerBottom, x + w, innerBottom + C_BOTTOM, color);          // bottom bar
        ctx.fill(x, innerBottom + C_BOTTOM - 1, x + w, innerBottom + C_BOTTOM, dark);
        tabs.add(new int[]{x, innerBottom + C_BOTTOM, color});
        if (live) {
            nextHits.add(0, new Hit(x, y, x + w, innerTop, HitType.BLOCK, si, s, list, -1));
            nextHits.add(0, new Hit(x, innerTop, x + ARM, innerBottom, HitType.BLOCK, si, s, list, -1));
            nextHits.add(0, new Hit(x, innerBottom, x + w, innerBottom + C_BOTTOM, HitType.BLOCK, si, s, list, -1));
            blockRects.put(s.id, new int[]{x, y, x + w, color});
        }
        drawSegments(ctx, x + 8, y, segs, si, s, live, dark);
        if (size != null) size[0] = w;
        return innerBottom + C_BOTTOM;
    }

    /** The coloured "strings" from each "if done" socket in a script to the block it's tied to. */
    private void drawStrings(DrawContext ctx, Program p, int mx, int my) {
        List<Program.Step> all = p.allSteps();
        int laneBase = 0;
        for (Program.Step s : all) {
            int[] rect = blockRects.get(s.id);
            if (rect != null) laneBase = Math.max(laneBase, rect[2]);
        }
        laneBase += 8;
        int lane = 0;
        for (Program.Step s : all) {
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
        if (linkFrom != null && all.contains(linkFrom) && sockets.containsKey(linkFrom.id)) {   // string following the mouse
            int[] sock = sockets.get(linkFrom.id);
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
        if (s.kind == Program.Kind.HOLD_KEY) return SENSING;
        return s.kind.isControl() ? CONTROL : MOTION;
    }

    private static int darkOf(Program.Step s) {
        if (s.kind == Program.Kind.HOLD_KEY) return SENSING_DARK;
        return s.kind.isControl() ? CONTROL_DARK : MOTION_DARK;
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
            nextHits.add(new Hit(m.x, iy, m.x + m.w, iy + ITEM_H, HitType.MENU_ITEM, -1, null, null, i));
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
            nextHits.add(new Hit(bx, y, bx + bw, y + 20, HitType.BUTTON, -1, null, null, i));
        }
    }

    // ------------------------------------------------------------------ block text

    private List<Seg> segmentsFor(Program.Step s) {
        return switch (s.kind) {
            case WALK_WALL -> List.of(new Seg(SegType.LABEL, "walk"), new Seg(SegType.DIR, s.dir.label()),
                    new Seg(SegType.MODE, "until wall"));
            case WALK_BLOCKS -> List.of(new Seg(SegType.LABEL, "walk"), new Seg(SegType.DIR, s.dir.label()),
                    new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.MODE, "blocks"));
            case WAIT -> List.of(new Seg(SegType.LABEL, "wait"), new Seg(SegType.NUM, String.valueOf(s.amount)),
                    new Seg(SegType.LABEL, s.amount == 1 ? "second" : "seconds"));
            case REPEAT -> s.amount == 0
                    ? List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.RMODE, "forever"))
                    : List.of(new Seg(SegType.LABEL, "repeat"), new Seg(SegType.REPEAT, String.valueOf(s.amount)),
                    new Seg(SegType.RMODE, "times"));
            case IF -> List.of(new Seg(SegType.LABEL, "if"), new Seg(SegType.LABEL, "done"),
                    new Seg(SegType.LINK, ""));
            case IF_AT -> List.of(new Seg(SegType.LABEL, "if at x"),
                    new Seg(SegType.NUM, String.valueOf(s.amount)), new Seg(SegType.LABEL, "z"),
                    new Seg(SegType.NUM2, String.valueOf(s.amount2)));
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
            case HOLD_KEY -> List.of(new Seg(SegType.LABEL, "hold"),
                    new Seg(SegType.HOLDKEY, capturingStep == s ? "press a key / click"
                            : Program.inputName(s.amount, s.amount3 == 1)),
                    new Seg(SegType.LABEL, "for"), new Seg(SegType.NUM2, Program.tenths(s.amount2)),
                    new Seg(SegType.LABEL, "s"));
            case WAIT_RANDOM -> List.of(new Seg(SegType.LABEL, "random wait"),
                    new Seg(SegType.NUM, Program.tenths(s.amount)), new Seg(SegType.LABEL, "to"),
                    new Seg(SegType.NUM2, Program.tenths(s.amount2)), new Seg(SegType.LABEL, "s"));
        };
    }

    private int segWidth(Seg seg) {
        return switch (seg.type) {
            case LABEL -> textRenderer.getWidth(seg.text);
            case DIR, MODE, RMODE -> textRenderer.getWidth(seg.text + " ▾") + 10;
            case NUM, NUM2, NUM3, REPEAT, KEY, HOLDKEY -> Math.max(20, textRenderer.getWidth(seg.text) + 12);
            case LINK -> 14;
            case RUN -> 16;
        };
    }

    private int segmentsWidth(List<Seg> segs) {
        int w = 0;
        for (Seg s : segs) w += segWidth(s) + 5;
        return w - 5;
    }

    /** Draws a block's words and fields; when editable, records their click areas. */
    private void drawSegments(DrawContext ctx, int x, int y, List<Seg> segs, int si, Program.Step step,
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
                    if (editable && step != null) nextHits.add(new Hit(tx, fy1, tx + w, fy2, ht, si, step, null, -1));
                }
                case LINK -> {
                    int cx = tx + 7, cyy = y + BH / 2;
                    Program.Step linked = editable && p != null && step != null && step.link >= 0
                            ? p.byId(step.link) : null;
                    int fillColor = linked != null ? colorOf(linked) : WHITE;
                    pill(ctx, cx - 6, cyy - 6, 12, 12, FIELD_TEXT);   // socket ring
                    pill(ctx, cx - 4, cyy - 4, 8, 8, fillColor);
                    if (editable && step != null) {
                        sockets.put(step.id, new int[]{cx + 6, cyy});
                        nextHits.add(new Hit(cx - 7, cyy - 7, cx + 7, cyy + 7, HitType.LINK, si, step, null, -1));
                    }
                }
                case RUN -> {
                    pill(ctx, tx, fy1, w, BH - 8, GREEN);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            WHITE, false);
                    if (editable && p != null) nextHits.add(new Hit(tx, fy1, tx + w, fy2, HitType.RUN, si, null, null, -1));
                }
                case NUM, NUM2, NUM3, REPEAT, KEY, HOLDKEY -> {
                    boolean picking = (seg.type == SegType.KEY && capturing == si && si >= 0)
                            || (seg.type == SegType.HOLDKEY && step != null && capturingStep == step);
                    pill(ctx, tx, fy1, w, BH - 8, picking ? CAPTURE_BG : WHITE);
                    ctx.drawText(textRenderer, seg.text, tx + (w - textRenderer.getWidth(seg.text)) / 2, ty,
                            FIELD_TEXT, false);
                    if (!editable) break;
                    HitType ht = switch (seg.type) {
                        case NUM -> HitType.NUM;
                        case NUM2 -> HitType.NUM2;
                        case NUM3 -> HitType.NUM3;
                        case REPEAT -> HitType.REPEAT;
                        case HOLDKEY -> HitType.HOLDKEY;
                        default -> HitType.KEY;
                    };
                    if (ht == HitType.KEY ? p != null : step != null)
                        nextHits.add(new Hit(tx, fy1, tx + w, fy2, ht, si, step, null, -1));
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
