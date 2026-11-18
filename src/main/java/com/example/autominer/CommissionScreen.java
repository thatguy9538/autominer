package com.example.autominer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Commissions menu: start/stop, what it's doing, your commissions, and where each area is. */
public class CommissionScreen extends Screen {

    private final Screen parent;
    private int left, w, areasTop;
    private static final int ROW_H = 22;

    public CommissionScreen(Screen parent) {
        super(Text.literal("Auto Miner — Commissions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(380, width - 20);
        left = (width - w) / 2;
        int y = 26;

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Commissions: ").append(CommissionBot.running
                        ? Text.literal("RUNNING").formatted(Formatting.GREEN) : Text.literal("OFF").formatted(Formatting.RED)),
                b -> {
                    CommissionBot.setRunning(client, !CommissionBot.running);
                    if (CommissionBot.running) close();       // start right away
                    else clearAndInit();
                }).dimensions(left, y, w - 124, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Travel: " + (CommissionBot.useEtherwarp ? "Etherwarp" : "Walk")),
                b -> {
                    CommissionBot.useEtherwarp = !CommissionBot.useEtherwarp;
                    CommissionBot.save();
                    clearAndInit();
                }).dimensions(left + w - 120, y, 120, 20).build());

        areasTop = y + 26 + 12 + 6 * 10 + 14;
        int rows = CommissionBot.AREAS.size();
        int maxRows = Math.max(1, (height - 30 - areasTop) / ROW_H);
        for (int i = 0; i < Math.min(rows, maxRows); i++) {
            String area = CommissionBot.AREAS.get(i);
            int ry = areasTop + i * ROW_H;
            addDrawableChild(ButtonWidget.builder(Text.literal("Set here"), b -> {
                if (client.player != null) CommissionBot.setLocation(area, client.player.getBlockPos());
                clearAndInit();
            }).dimensions(left + w - 104, ry, 70, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("✕").formatted(Formatting.RED), b -> {
                CommissionBot.setLocation(area, null);
                clearAndInit();
            }).dimensions(left + w - 30, ry, 30, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> client.setScreen(parent))
                .dimensions(left, height - 26, w, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
        int y = 52;

        ctx.drawTextWithShadow(textRenderer, Text.literal("Now: ").formatted(Formatting.GRAY)
                .append(Text.literal(textRenderer.trimToWidth(CommissionBot.status, w - 30))), left, y, 0xFFFFFFFF);
        y += 12;

        List<CommissionBot.Commission> list = CommissionBot.readCommissions(client);
        if (list.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, "No commissions in the tab list (go to the Dwarven Mines).",
                    left, y, 0xFFAAAAAA);
        }
        for (int i = 0; i < Math.min(list.size(), 6); i++) {
            CommissionBot.Commission c = list.get(i);
            int color = c.done() ? 0xFF55FF55 : 0xFFFFFFFF;
            ctx.drawTextWithShadow(textRenderer, "• " + c.name() + ": " + c.progress(), left, y + i * 10, color);
        }

        ctx.drawTextWithShadow(textRenderer, Text.literal("Locations (stand there, then click Set here)")
                .formatted(Formatting.YELLOW), left, areasTop - 13, 0xFFFFFFFF);
        int maxRows = Math.max(1, (height - 30 - areasTop) / ROW_H);
        for (int i = 0; i < Math.min(CommissionBot.AREAS.size(), maxRows); i++) {
            String area = CommissionBot.AREAS.get(i);
            int ry = areasTop + i * ROW_H;
            BlockPos p = CommissionBot.locations.get(area);
            ctx.fill(left, ry, left + w - 108, ry + 20, 0x60000000);
            ctx.drawTextWithShadow(textRenderer, area, left + 6, ry + 6, 0xFFFFFFFF);
            String where = p == null ? "not set" : p.getX() + ", " + p.getY() + ", " + p.getZ();
            ctx.drawTextWithShadow(textRenderer, where, left + w - 112 - textRenderer.getWidth(where), ry + 6,
                    p == null ? 0xFFFF5555 : 0xFF55FF55);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        client.setScreen(null);
    }
}
