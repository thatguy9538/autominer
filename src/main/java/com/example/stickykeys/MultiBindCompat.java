package com.example.stickykeys;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Works with the Multi Bind mod when it's installed: a binding's extra keys from Multi Bind latch
 * it just like its own key. Looked up by reflection, so Sticky Keys still runs without Multi Bind.
 */
final class MultiBindCompat {

    private MultiBindCompat() {
    }

    private static boolean looked = false;
    private static Method extras = null;

    @SuppressWarnings("unchecked")
    static List<InputUtil.Key> extras(KeyBinding b) {
        if (!looked) {
            looked = true;
            if (FabricLoader.getInstance().isModLoaded("multibind")) {
                try {
                    extras = Class.forName("com.example.multibind.MultiBind").getMethod("extras", KeyBinding.class);
                } catch (Exception e) {
                    extras = null;
                }
            }
        }
        if (extras == null) return List.of();
        try {
            Object r = extras.invoke(null, b);
            return r instanceof List<?> l ? (List<InputUtil.Key>) l : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
