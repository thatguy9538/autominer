package com.example.multibind;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Multi Bind: more than one key for any action. Settings in Mod Menu (or an optional key). */
public class MultiBindClient implements ClientModInitializer {

    public static final String MOD_ID = "multibind";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        MultiBind.load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.multibind.menu",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new MultiBindScreen(null));
            }
        });
    }
}
