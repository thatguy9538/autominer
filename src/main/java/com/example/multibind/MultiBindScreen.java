package com.example.multibind;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi Bind settings: every key binding with its own key and its extra keys. "+ Add key" then
 * press a key or mouse button to add one; click an extra key to remove it.
 */
public class MultiBindScreen extends Screen {

    private static final int ROW_H = 24;
    private static final int ACCENT = 0xFF5AA9FF, PANEL = 0xC0101418, ROW = 0x40FFFFFF, ROW_EXTRA = 0x305AA9FF;

    private final Screen parent;
    private TextFieldWidget search;
    private String query = "";
    private boolean searchFocused = false;
    private boolean onlyWithExtras = false;
    private int scroll = 0;
    private int left, w, listTop, listBottom;
    /** Binding we're waiting for a key for (null = not listening). */
    private KeyBinding capturing = null;
    private String message = "";

    private record Entry(String header, KeyBinding binding) {}

    private final List<Entry> entries = new ArrayList<>();

    public MultiBindScreen(Screen parent) {
        super(Text.literal("Multi Bind"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(600, width - 20);
        left = (width - w) / 2;
        int y = 34;

        search = new TextFieldWidget(textRenderer, left, y, w - 164, 20, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search actions or keys...").formatted(Formatting.DARK_GRAY));
        search.setText(query);
        search.setChangedListener(s -> {
            query = s;
            scroll = 0;
            searchFocused = true;
            clearAndInit();
        });
        addDrawableChild(search);
        if (searchFocused) {
            setFocused(search);
            search.setFocused(true);
        }
        button(left + w - 160, y, 160, Text.literal(onlyWithExtras ? "Showing: with extra keys" : "Showing: all actions"),
                null, () -> {
                    onlyWithExtras = !onlyWithExtras;
                    scroll = 0;
                });
        y += 28;

        listTop = y;
        listBottom = height - 46;
        buildEntries();
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - visible)));
        for (int i = 0; i < visible && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            if (e.binding() == null) continue;
            addRowButtons(e.binding(), listTop + i * ROW_H);
        }

        int fw = (w - 4) / 2;
        button(left, height - 26, fw, Text.literal("Remove all extra keys"), null, () -> {
            MultiBind.clearAll();
            message = "Removed every extra key.";
        });
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(left + fw + 4, height - 26, w - fw - 4, 20).build());
    }

    /** Row: [extra key] [extra key] ... [+ Add key], right-aligned; the name and own key are drawn in render(). */
    private void addRowButtons(KeyBinding b, int ry) {
        int x = left + w - 4;
        boolean listening = capturing == b;
        boolean full = MultiBind.extras(b).size() >= MultiBind.MAX_EXTRA;
        int addW = listening ? 92 : 62;
        x -= addW;
        if (!full || listening) {
            button(x, ry + 2, addW, listening ? Text.literal("press a key...").formatted(Formatting.YELLOW)
                            : Text.literal("+ Add key").formatted(Formatting.AQUA),
                    Tooltip.of(Text.literal("Then press a key or mouse button. Esc cancels.")),
                    () -> capturing = listening ? null : b);
        }
        List<InputUtil.Key> extras = new ArrayList<>(MultiBind.extras(b));
        for (int i = extras.size() - 1; i >= 0; i--) {
            InputUtil.Key k = extras.get(i);
            String name = k.getLocalizedText().getString();
            int bw = Math.min(110, textRenderer.getWidth(name) + 22);
            x -= bw + 3;
            List<KeyBinding> clash = MultiBind.usedBy(k, b);
            MutableText label = Text.literal(name + " ✕").formatted(clash.isEmpty() ? Formatting.WHITE : Formatting.GOLD);
            String tip = "Click to remove this extra key.";
            if (!clash.isEmpty()) tip += "\nAlso used by: " + nameOf(clash.get(0)) + (clash.size() > 1 ? " and " + (clash.size() - 1) + " more" : "");
            button(x, ry + 2, bw, label, Tooltip.of(Text.literal(tip)), () -> {
                MultiBind.remove(b, k);
                message = "Removed " + name + " from " + nameOf(b) + ".";
            });
        }
    }

    private void buildEntries() {
        entries.clear();
        List<KeyBinding> all = MultiBind.bindings();
        all.sort(null);                                    // by category, then name (like Controls)
        String q = query.toLowerCase(Locale.ROOT).trim();
        Object lastCat = null;
        for (KeyBinding b : all) {
            if (b.getId().startsWith("key.multibind.")) continue;
            if (onlyWithExtras && MultiBind.extras(b).isEmpty()) continue;
            if (!q.isEmpty() && !matches(b, q)) continue;
            if (!b.getCategory().equals(lastCat)) {
                lastCat = b.getCategory();
                var id = b.getCategory().id();
                entries.add(new Entry(Text.translatable("key.category." + id.getNamespace() + "." + id.getPath()).getString(), null));
            }
            entries.add(new Entry(null, b));
        }
    }

    private static boolean matches(KeyBinding b, String q) {
        if (nameOf(b).toLowerCase(Locale.ROOT).contains(q)) return true;
        if (b.getBoundKeyLocalizedText().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (InputUtil.Key k : MultiBind.extras(b)) {
            if (k.getLocalizedText().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static String nameOf(KeyBinding b) {
        return Text.translatable(b.getId()).getString();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    private void button(int x, int y, int bw, Text label, Tooltip tip, Runnable action) {
        ButtonWidget.Builder bld = ButtonWidget.builder(label, btn -> {
            action.run();
            searchFocused = false;
            clearAndInit();
        }).dimensions(x, y, bw, 20);
        if (tip != null) bld.tooltip(tip);
        addDrawableChild(bld.build());
    }

    // ------------------------------------------------------------------ capturing a key

    private void captured(InputUtil.Key key) {
        KeyBinding b = capturing;
        capturing = null;
        if (b == null) return;
        String name = key.getLocalizedText().getString();
        if (key.equals(KeyBindingHelper.getBoundKeyOf(b))) {
            message = name + " is already " + nameOf(b) + "'s own key.";
        } else if (MultiBind.add(b, key)) {
            List<KeyBinding> clash = MultiBind.usedBy(key, b);
            message = "Added " + name + " to " + nameOf(b) + "."
                    + (clash.isEmpty() ? "" : " Note: it also triggers " + nameOf(clash.get(0)) + ".");
        } else {
            message = MultiBind.extras(b).contains(key) ? name + " is already an extra key for " + nameOf(b) + "."
                    : nameOf(b) + " already has " + MultiBind.MAX_EXTRA + " extra keys.";
        }
        searchFocused = false;
        clearAndInit();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (capturing != null) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                capturing = null;
                message = "Cancelled.";
                clearAndInit();
            } else {
                captured(InputUtil.Type.KEYSYM.createFromCode(input.key()));
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (capturing != null) {
            captured(InputUtil.Type.MOUSE.createFromCode(click.button()));
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int max = Math.max(0, entries.size() - visibleRows());
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount)));   // one row per notch
        if (next != scroll) {
            scroll = next;
            searchFocused = search != null && search.isFocused();
            clearAndInit();
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(left - 6, 6, left + w + 6, height - 4, PANEL);
        ctx.fill(left - 6, 6, left + w + 6, 7, ACCENT);
        for (int i = 0; i < visibleRows() && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            if (e.binding() == null) continue;
            int ry = listTop + i * ROW_H;
            boolean has = !MultiBind.extras(e.binding()).isEmpty();
            boolean listening = capturing == e.binding();
            ctx.fill(left, ry, left + w, ry + ROW_H - 2, listening ? 0x50FFDD55 : has ? ROW_EXTRA : ROW);
            if (has) ctx.fill(left, ry, left + 2, ry + ROW_H - 2, ACCENT);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Multi Bind").formatted(Formatting.BOLD), width / 2, 12, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Give any action extra keys: each one works just like its own key",
                width / 2, 22, 0xFF9AA0A6);

        for (int i = 0; i < visibleRows() && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            int ry = listTop + i * ROW_H;
            if (e.binding() == null) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(e.header()).formatted(Formatting.YELLOW, Formatting.BOLD),
                        left + 2, ry + 9, 0xFFFFFFFF);
                continue;
            }
            KeyBinding b = e.binding();
            // own key chip, then the action's name
            String own = b.isUnbound() ? "not bound" : b.getBoundKeyLocalizedText().getString();
            int ow = Math.max(44, textRenderer.getWidth(own) + 10);
            int ox = left + 6;
            ctx.fill(ox, ry + 4, ox + ow, ry + ROW_H - 6, 0xA0000000);
            ctx.drawCenteredTextWithShadow(textRenderer, own, ox + ow / 2, ry + 7, b.isUnbound() ? 0xFF777777 : 0xFFDDDDDD);
            int chipsLeft = left + w - 4 - (capturing == b ? 92 : 62);
            for (InputUtil.Key k : MultiBind.extras(b)) {
                chipsLeft -= Math.min(110, textRenderer.getWidth(k.getLocalizedText().getString()) + 22) + 3;
            }
            int nameW = Math.max(20, chipsLeft - (ox + ow + 8) - 6);
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(nameOf(b), nameW), ox + ow + 8, ry + 7, 0xFFFFFFFF);
        }
        if (entries.isEmpty()) {
            String why = onlyWithExtras && query.isBlank() ? "No action has extra keys yet. Switch to \"all actions\" and press + Add key."
                    : "No actions match \"" + query + "\".";
            ctx.drawCenteredTextWithShadow(textRenderer, why, width / 2, listTop + 10, 0xFFAAAAAA);
        }
        if (entries.size() > visibleRows()) {
            int trackH = listBottom - listTop;
            int barH = Math.max(12, trackH * visibleRows() / entries.size());
            int barY = listTop + (trackH - barH) * scroll / Math.max(1, entries.size() - visibleRows());
            ctx.fill(left + w + 2, listTop, left + w + 4, listBottom, 0x40FFFFFF);
            ctx.fill(left + w + 2, barY, left + w + 4, barY + barH, 0xFFAAAAAA);
        }
        String foot = capturing != null ? "Press a key or mouse button to add it to " + nameOf(capturing) + "  (Esc cancels)" : message;
        if (!foot.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, foot, width / 2, height - 38, capturing != null ? 0xFFFFDD55 : 0xFFB8E0FF);
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
