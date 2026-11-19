package com.example.stickykeys;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sticky Keys settings: a searchable list of every key binding, grouped by category, with a
 * switch on each row to make it sticky. Opened from Mod Menu (or an optional key).
 */
public class StickyScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int ACCENT = 0xFF55FF55, PANEL = 0xC0101418, ROW = 0x40FFFFFF, ROW_STICKY = 0x3055FF55;

    private final Screen parent;
    private TextFieldWidget search;
    private String query = "";
    private boolean searchFocused = false;
    /** Waiting for a key press to set the on/off key. */
    private boolean listening = false;
    private int toggleKeyRowY = 0;
    private int scroll = 0;
    private int left, w, listTop, listBottom;

    /** One line of the list: a category header (binding == null) or a binding. */
    private record Entry(String header, KeyBinding binding) {}

    private final List<Entry> entries = new ArrayList<>();

    public StickyScreen(Screen parent) {
        super(Text.literal("Sticky Keys"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(420, width - 20);
        left = (width - w) / 2;
        int y = 32;

        // global switches
        int bw = (w - 12) / 4;
        button(left, y, bw, toggle("Sticky Keys", Sticky.enabled), () -> Sticky.setEnabled(!Sticky.enabled));
        button(left + (bw + 4), y, bw, toggle("Release in menus", Sticky.releaseInMenus), () -> {
            Sticky.releaseInMenus = !Sticky.releaseInMenus;
            Sticky.save();
        });
        button(left + 2 * (bw + 4), y, bw, toggle("Held keys HUD", Sticky.hud), () -> {
            Sticky.hud = !Sticky.hud;
            Sticky.save();
        });
        String[] spots = {"Top left", "Top right", "Bottom left", "Bottom right", "Above hotbar"};
        button(left + 3 * (bw + 4), y, w - 3 * (bw + 4), Text.literal("HUD: " + spots[Sticky.hudCorner]), () -> {
            Sticky.hudCorner = (Sticky.hudCorner + 1) % 5;
            Sticky.save();
        });
        y += 24;

        // the key that turns every sticky key on / off
        KeyBinding tk = StickyKeysClient.toggleKey;
        Text keyText = listening
                ? Text.literal("> press a key <").formatted(Formatting.YELLOW)
                : tk.isUnbound() ? Text.literal("not bound").formatted(Formatting.GRAY)
                : tk.getBoundKeyLocalizedText().copy().formatted(Formatting.AQUA);
        int kb = 120;
        button(left + w - kb, y, kb, keyText, () -> listening = !listening);
        toggleKeyRowY = y;
        y += 26;

        // search
        search = new TextFieldWidget(textRenderer, left, y, w, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search keys...").formatted(Formatting.DARK_GRAY));
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
        y += 24;

        // the list
        listTop = y;
        listBottom = height - 34;
        buildEntries();
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - visible)));
        for (int i = 0; i < visible && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            if (e.binding() == null) continue;
            KeyBinding b = e.binding();
            boolean on = Sticky.sticky.contains(b.getId());
            int ry = listTop + i * ROW_H;
            button(left + w - 84, ry + 1, 80, on
                    ? Text.literal("STICKY").formatted(Formatting.GREEN, Formatting.BOLD)
                    : Text.literal("normal").formatted(Formatting.GRAY), () -> Sticky.setSticky(b, !on));
        }

        // footer
        int fw = (w - 8) / 3;
        button(left, height - 26, fw, Text.literal("Let go of all"), Sticky::releaseAll);
        button(left + fw + 4, height - 26, fw, Text.literal("Make none sticky"), () -> {
            for (KeyBinding b : Sticky.bindings()) if (Sticky.sticky.contains(b.getId())) Sticky.setSticky(b, false);
        });
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(left + 2 * (fw + 4), height - 26, w - 2 * (fw + 4), 20).build());
    }

    private void buildEntries() {
        entries.clear();
        List<KeyBinding> all = Sticky.bindings();
        all.sort(null);                                    // by category, then name (like Controls)
        String q = query.toLowerCase(Locale.ROOT).trim();
        Object lastCat = null;
        for (KeyBinding b : all) {
            String name = name(b);
            String key = b.getBoundKeyLocalizedText().getString();
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !key.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (!b.getCategory().equals(lastCat)) {
                lastCat = b.getCategory();
                entries.add(new Entry(categoryName(b), null));
            }
            entries.add(new Entry(null, b));
        }
    }

    private static String name(KeyBinding b) {
        return Text.translatable(b.getId()).getString();
    }

    private static String categoryName(KeyBinding b) {
        var id = b.getCategory().id();
        return Text.translatable("key.category." + id.getNamespace() + "." + id.getPath()).getString();
    }

    private void button(int x, int y, int bw, Text label, Runnable action) {
        addDrawableChild(ButtonWidget.builder(label, btn -> {
            boolean wasListening = listening;
            action.run();
            if (wasListening && listening) listening = false;   // any other click cancels
            searchFocused = false;
            clearAndInit();
        }).dimensions(x, y, bw, 20).build());
    }

    private static MutableText toggle(String what, boolean on) {
        return Text.literal(what + ": ").append(on ? Text.literal("ON").formatted(Formatting.GREEN)
                : Text.literal("OFF").formatted(Formatting.RED));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        // panel behind everything, rows drawn under their buttons
        ctx.fill(left - 6, 6, left + w + 6, height - 4, PANEL);
        ctx.fill(left - 6, 6, left + w + 6, 7, ACCENT);
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        for (int i = 0; i < visible && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            int ry = listTop + i * ROW_H;
            if (e.binding() == null) continue;
            boolean on = Sticky.sticky.contains(e.binding().getId());
            ctx.fill(left, ry, left + w, ry + ROW_H - 2, on ? ROW_STICKY : ROW);
            if (on) ctx.fill(left, ry, left + 2, ry + ROW_H - 2, ACCENT);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Sticky Keys").formatted(Formatting.BOLD),
                width / 2, 12, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Press a sticky key once to hold it, press it again to let go",
                width / 2, 22, 0xFF9AA0A6);

        // on/off key row
        ctx.fill(left, toggleKeyRowY, left + w, toggleKeyRowY + 20, 0x40FFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("On / off key").formatted(Formatting.BOLD),
                left + 8, toggleKeyRowY + 6, 0xFFFFFFFF);
        String hint = listening ? "Esc = cancel, Backspace = unbind" : "turns every sticky key on and off";
        ctx.drawTextWithShadow(textRenderer, hint, left + 80, toggleKeyRowY + 6, 0xFF9AA0A6);

        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        for (int i = 0; i < visible && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            int ry = listTop + i * ROW_H;
            if (e.binding() == null) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(e.header()).formatted(Formatting.YELLOW, Formatting.BOLD),
                        left + 2, ry + 8, 0xFFFFFFFF);
                ctx.fill(left + 4 + textRenderer.getWidth(e.header()) + 6, ry + 12, left + w, ry + 13, 0x40FFFF55);
                continue;
            }
            KeyBinding b = e.binding();
            boolean latched = Sticky.isLatched(b);
            int tx = left + 8;
            if (latched) {                                 // a little "held" dot
                ctx.fill(tx, ry + 7, tx + 5, ry + 12, ACCENT);
                tx += 9;
            }
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(name(b), w - 200), tx, ry + 6, 0xFFFFFFFF);
            String key = b.isUnbound() ? "not bound" : b.getBoundKeyLocalizedText().getString();
            int kw = textRenderer.getWidth(key) + 8;
            int kx = left + w - 92 - kw;
            ctx.fill(kx, ry + 3, kx + kw, ry + 17, 0x80000000);
            ctx.drawTextWithShadow(textRenderer, key, kx + 4, ry + 6, b.isUnbound() ? 0xFF777777 : 0xFFDDDDDD);
        }
        if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "No keys match \"" + query + "\"", width / 2, listTop + 10, 0xFFAAAAAA);
        }

        // scrollbar
        if (entries.size() > visible) {
            int trackH = listBottom - listTop;
            int barH = Math.max(12, trackH * visible / entries.size());
            int barY = listTop + (trackH - barH) * scroll / Math.max(1, entries.size() - visible);
            ctx.fill(left + w + 2, listTop, left + w + 4, listBottom, 0x40FFFFFF);
            ctx.fill(left + w + 2, barY, left + w + 4, barY + barH, 0xFFAAAAAA);
        }
    }

    /** While listening: the next key becomes the on/off key (Esc cancels, Backspace/Delete unbinds). */
    @Override
    public boolean keyPressed(KeyInput input) {
        if (listening) {
            int code = input.key();
            listening = false;
            if (code == GLFW.GLFW_KEY_BACKSPACE || code == GLFW.GLFW_KEY_DELETE) {
                StickyKeysClient.setToggleKey(client, InputUtil.UNKNOWN_KEY);
            } else if (code != GLFW.GLFW_KEY_ESCAPE) {
                StickyKeysClient.setToggleKey(client, InputUtil.Type.KEYSYM.createFromCode(code));
            }
            searchFocused = false;
            clearAndInit();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        int max = Math.max(0, entries.size() - visible);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount) * 3));
        if (next != scroll) {
            scroll = next;
            searchFocused = search != null && search.isFocused();
            clearAndInit();
        }
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
