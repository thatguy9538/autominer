package com.example.powderassist;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Settings menu (U). */
public class PowderScreen extends Screen {

    private int left, w;

    public PowderScreen() {
        super(Text.literal("Powder Assist"));
    }

    @Override
    protected void init() {
        w = Math.min(320, width - 20);
        left = (width - w) / 2;
        int half = (w - 4) / 2;
        int y = 34;

        // auto-mine
        add(left, y, w, toggle("Auto-mine (J)", PowderAssistClient.autoMine), () ->
                PowderAssistClient.setAutoMine(client, !PowderAssistClient.autoMine));
        y += 24;
        int third = (w - 8) / 3;
        add(left, y, third, toggle("Hardstone", PowderAssistClient.mineHardstone), () ->
                PowderAssistClient.mineHardstone = !PowderAssistClient.mineHardstone);
        add(left + third + 4, y, third, toggle("Gemstones", PowderAssistClient.mineGemstones), () ->
                PowderAssistClient.mineGemstones = !PowderAssistClient.mineGemstones);
        add(left + 2 * (third + 4), y, w - 2 * (third + 4), toggle("Ores", PowderAssistClient.mineOres), () ->
                PowderAssistClient.mineOres = !PowderAssistClient.mineOres);
        y += 24;
        add(left, y, 40, Text.literal("-"), () -> PowderAssistClient.turnSpeed = Math.max(1, PowderAssistClient.turnSpeed - 1));
        add(left + w - 40, y, 40, Text.literal("+"), () -> PowderAssistClient.turnSpeed = Math.min(10, PowderAssistClient.turnSpeed + 1));
        y += 34;

        // chest solver
        add(left, y, half, toggle("Chest solver (N)", PowderAssistClient.solver), () ->
                PowderAssistClient.solver = !PowderAssistClient.solver);
        add(left + half + 4, y, w - half - 4, toggle("Auto-click", PowderAssistClient.solverAutoClick), () ->
                PowderAssistClient.solverAutoClick = !PowderAssistClient.solverAutoClick);
        y += 24;
        add(left, y, w, Text.literal("Lockpick with: ").append(Text.literal(
                PowderAssistClient.solverRightClick ? "Right-click" : "Left-click").formatted(Formatting.YELLOW)), () ->
                PowderAssistClient.solverRightClick = !PowderAssistClient.solverRightClick);
        y += 34;

        // ESP + HUD
        add(left, y, half, toggle("Chest ESP", PowderAssistClient.esp), () -> PowderAssistClient.esp = !PowderAssistClient.esp);
        add(left + half + 4, y, w - half - 4, toggle("Tracers", PowderAssistClient.tracers), () ->
                PowderAssistClient.tracers = !PowderAssistClient.tracers);
        y += 24;
        String[] corners = {"Top left", "Top right", "Bottom left", "Bottom right"};
        add(left, y, half, toggle("Tracker HUD", PowderAssistClient.hud), () -> PowderAssistClient.hud = !PowderAssistClient.hud);
        add(left + half + 4, y, w - half - 4, Text.literal("HUD: " + corners[PowderAssistClient.hudCorner]), () ->
                PowderAssistClient.hudCorner = (PowderAssistClient.hudCorner + 1) % 4);
        y += 24;
        add(left, y, w, Text.literal("Reset session (powder, chests, time)"), Tracker::reset);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(left, height - 26, w, 20).build());
    }

    private void add(int x, int y, int bw, Text label, Runnable action) {
        addDrawableChild(ButtonWidget.builder(label, b -> {
            action.run();
            PowderAssistClient.save();
            clearAndInit();
        }).dimensions(x, y, bw, 20).build());
    }

    private static MutableText toggle(String what, boolean on) {
        return Text.literal(what + ": ").append(on ? Text.literal("ON").formatted(Formatting.GREEN)
                : Text.literal("OFF").formatted(Formatting.RED));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Mining").formatted(Formatting.YELLOW), left, 24, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Turn speed: " + PowderAssistClient.turnSpeed + " / 10",
                width / 2, 34 + 48 + 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Treasure chests").formatted(Formatting.YELLOW),
                left, 34 + 82 - 10, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Display").formatted(Formatting.YELLOW),
                left, 34 + 82 + 58 - 10, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
