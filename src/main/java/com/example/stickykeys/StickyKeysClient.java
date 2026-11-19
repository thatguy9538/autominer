package com.example.stickykeys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class StickyKeysClient implements ClientModInitializer {

    public static final String MOD_ID = "stickykeys";
    private static KeyBinding menuKey, toggleKey, releaseKey;

    @Override
    public void onInitializeClient() {
        Sticky.load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stickykeys.menu",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stickykeys.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        releaseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stickykeys.release",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        ClientTickEvents.START_CLIENT_TICK.register(Sticky::tick);
        ClientTickEvents.END_CLIENT_TICK.register(StickyKeysClient::handleKeys);
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), StickyKeysClient::renderHud);
    }

    private static void handleKeys(MinecraftClient client) {
        while (menuKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new StickyScreen(null));
        }
        while (toggleKey.wasPressed()) {
            Sticky.setEnabled(!Sticky.enabled);
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Sticky Keys: ").append(Sticky.enabled
                        ? Text.literal("ON").formatted(Formatting.GREEN)
                        : Text.literal("OFF").formatted(Formatting.RED)), true);
            }
        }
        while (releaseKey.wasPressed()) Sticky.releaseAll();
    }

    /** Small "held" chips for every latched key. */
    private static void renderHud(DrawContext ctx, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Sticky.hud || mc.options.hudHidden || mc.player == null) return;
        List<KeyBinding> held = Sticky.latched();
        if (held.isEmpty()) return;
        var tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int total = 0;
        for (KeyBinding b : held) total += tr.getWidth(label(b)) + 12;
        total -= 2;
        int x, y;
        switch (Sticky.hudCorner) {
            case 0 -> { x = 4; y = 4; }
            case 1 -> { x = w - total - 4; y = 4; }
            case 2 -> { x = 4; y = h - 16; }
            case 3 -> { x = w - total - 4; y = h - 16; }
            default -> { x = (w - total) / 2; y = h - 62; }
        }
        for (KeyBinding b : held) {
            String s = label(b);
            int cw = tr.getWidth(s) + 10;
            ctx.fill(x, y, x + cw, y + 12, 0xB0102A10);
            ctx.fill(x, y, x + 2, y + 12, 0xFF55FF55);
            ctx.drawTextWithShadow(tr, s, x + 6, y + 2, 0xFFB8FFB8);
            x += cw + 2;
        }
    }

    private static String label(KeyBinding b) {
        return Text.translatable(b.getId()).getString();
    }
}
