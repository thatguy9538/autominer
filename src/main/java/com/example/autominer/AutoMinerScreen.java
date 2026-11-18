package com.example.autominer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Map;

/** The Auto Miner menu: on/off, mode, settings, and the target list with priorities. */
public class AutoMinerScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int[] PRIORITY_COLORS = {0xFFAAAAAA, 0xFF55FF55, 0xFFFFFF55, 0xFFFFAA00, 0xFFFF5555};
    private static final String[] PRIORITY_NAMES = {"Lowest", "Low", "Normal", "High", "Highest"};

    private int scroll = 0;
    private Text message = Text.empty();
    private int listTop, rowsShown, panelLeft, panelWidth;

    public AutoMinerScreen() {
        super(Text.literal("Auto Miner"));
    }

    @Override
    protected void init() {
        labels.clear();
        panelWidth = Math.min(360, width - 20);
        panelLeft = (width - panelWidth) / 2;
        int x = panelLeft, w = panelWidth;
        int y = 28;

        // ---- row 1: on/off, mode, add looked-at block
        int third = (w - 8) / 3;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Mining: ").append(AutoMinerClient.enabled
                        ? Text.literal("ON").formatted(Formatting.GREEN) : Text.literal("OFF").formatted(Formatting.RED)),
                b -> {
                    if (AutoMinerClient.targets.isEmpty()) {
                        message = Text.literal("Add a block first.").formatted(Formatting.RED);
                    } else {
                        AutoMinerClient.setEnabled(client, !AutoMinerClient.enabled);
                    }
                    clearAndInit();
                }).dimensions(x, y, third, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Mode: " + (AutoMinerClient.holdMode ? "Hold click" : "Classic")),
                b -> {
                    AutoMinerClient.setHoldMode(client, !AutoMinerClient.holdMode);
                    clearAndInit();
                }).dimensions(x + third + 4, y, third, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+ Add looked-at"),
                b -> {
                    message = AutoMinerClient.toggleLookedAt(client);
                    clearAndInit();
                }).dimensions(x + 2 * (third + 4), y, third, 20).build());
        y += 26;

        // ---- row 2: settings
        int sw = (w - 8) / 3;
        settingRow(x, y, sw, "Turn", Math.round(AutoMinerClient.turnFraction * 100) + "%",
                () -> AutoMinerClient.turnFraction = Math.max(0.05f, round2(AutoMinerClient.turnFraction - 0.05f)),
                () -> AutoMinerClient.turnFraction = Math.min(0.60f, round2(AutoMinerClient.turnFraction + 0.05f)));
        settingRow(x + sw + 4, y, sw, "Pause", (AutoMinerClient.pauseSetting * 50) + "ms",
                () -> AutoMinerClient.pauseSetting = Math.max(0, AutoMinerClient.pauseSetting - 1),
                () -> AutoMinerClient.pauseSetting = Math.min(40, AutoMinerClient.pauseSetting + 1));
        settingRow(x + 2 * (sw + 4), y, sw, "Path", String.valueOf(AutoMinerClient.maxPath),
                () -> AutoMinerClient.maxPath = Math.max(1, AutoMinerClient.maxPath - 1),
                () -> AutoMinerClient.maxPath = Math.min(40, AutoMinerClient.maxPath + 1));
        y += 34;

        // ---- target list
        listTop = y + 12;
        int bottomButtons = height - 28;
        rowsShown = Math.max(1, (bottomButtons - 30 - listTop) / ROW_H);
        List<Map.Entry<String, AutoMinerClient.Target>> list = AutoMinerClient.sortedTargets();
        scroll = Math.max(0, Math.min(scroll, list.size() - rowsShown));
        for (int i = 0; i < rowsShown && scroll + i < list.size(); i++) {
            Map.Entry<String, AutoMinerClient.Target> e = list.get(scroll + i);
            AutoMinerClient.Target t = e.getValue();
            int ry = listTop + i * ROW_H;
            int bx = x + w;
            bx -= 20;
            addDrawableChild(ButtonWidget.builder(Text.literal("✕").formatted(Formatting.RED), b -> {
                AutoMinerClient.targets.remove(e.getKey());
                AutoMinerClient.settingsChanged(client);
                clearAndInit();
            }).dimensions(bx, ry, 20, 20).build());
            bx -= 24;
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                t.priority = Math.min(AutoMinerClient.MAX_PRIORITY, t.priority + 1);
                AutoMinerClient.settingsChanged(client);
                clearAndInit();
            }).dimensions(bx, ry, 20, 20).build());
            bx -= 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
                t.priority = Math.max(AutoMinerClient.MIN_PRIORITY, t.priority - 1);
                AutoMinerClient.settingsChanged(client);
                clearAndInit();
            }).dimensions(bx, ry, 20, 20).build());
        }

        // ---- bottom
        int half = (w - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Commissions...").formatted(Formatting.AQUA),
                b -> client.setScreen(new CommissionScreen(this)))
                .dimensions(x, bottomButtons - 24, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear all").formatted(Formatting.RED), b -> {
            AutoMinerClient.clearTargets(client);
            clearAndInit();
        }).dimensions(x, bottomButtons, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(x + half + 4, bottomButtons, half, 20).build());
    }

    private void settingRow(int x, int y, int w, String label, String value, Runnable minus, Runnable plus) {
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
            minus.run();
            AutoMinerClient.settingsChanged(client);
            clearAndInit();
        }).dimensions(x, y + 10, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
            plus.run();
            AutoMinerClient.settingsChanged(client);
            clearAndInit();
        }).dimensions(x + w - 20, y + 10, 20, 20).build());
        labels.add(new Object[]{label, value, x, y, w});
    }

    private final java.util.List<Object[]> labels = new java.util.ArrayList<>();

    private static float round2(float f) {
        return Math.round(f * 100) / 100f;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);

        // setting labels and values
        for (Object[] l : labels) {
            int x = (int) l[2], y = (int) l[3], w = (int) l[4];
            ctx.drawCenteredTextWithShadow(textRenderer, (String) l[0], x + w / 2, y, 0xFFAAAAAA);
            ctx.drawCenteredTextWithShadow(textRenderer, (String) l[1], x + w / 2, y + 16, 0xFFFFFFFF);
        }

        // list heading
        ctx.drawTextWithShadow(textRenderer, Text.literal("Targets (higher priority is mined first)"),
                panelLeft, listTop - 12, 0xFFFFFFFF);

        List<Map.Entry<String, AutoMinerClient.Target>> list = AutoMinerClient.sortedTargets();
        if (list.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("No targets. Look at a block and click \"+ Add looked-at\" (or press G)."),
                    panelLeft, listTop + 6, 0xFFAAAAAA);
        }
        for (int i = 0; i < rowsShown && scroll + i < list.size(); i++) {
            AutoMinerClient.Target t = list.get(scroll + i).getValue();
            int ry = listTop + i * ROW_H;
            ctx.fill(panelLeft, ry, panelLeft + panelWidth - 70, ry + 20, 0x60000000);
            int p = Math.max(1, Math.min(5, t.priority));
            ctx.fill(panelLeft, ry, panelLeft + 3, ry + 20, PRIORITY_COLORS[p - 1]);
            String name = textRenderer.trimToWidth(t.name, panelWidth - 70 - 90);
            ctx.drawTextWithShadow(textRenderer, name, panelLeft + 8, ry + 6, 0xFFFFFFFF);
            String pr = p + " " + PRIORITY_NAMES[p - 1];
            ctx.drawTextWithShadow(textRenderer, pr,
                    panelLeft + panelWidth - 74 - textRenderer.getWidth(pr), ry + 6, PRIORITY_COLORS[p - 1]);
        }
        if (list.size() > rowsShown) {
            String more = (scroll + 1) + "-" + Math.min(list.size(), scroll + rowsShown) + " of " + list.size() + " (scroll)";
            ctx.drawTextWithShadow(textRenderer, more, panelLeft + panelWidth - textRenderer.getWidth(more),
                    listTop - 12, 0xFFAAAAAA);
        }

        if (!message.getString().isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, message, width / 2, height - 64, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical == 0) return false;
        scroll = Math.max(0, scroll - (int) Math.signum(vertical));
        clearAndInit();
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
