package com.example.stickykeys.mixin;

import com.example.stickykeys.Sticky;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sees every real key / mouse button press and release (only while no menu is open). */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Inject(method = "setKeyPressed", at = @At("HEAD"))
    private static void stickykeys$before(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        Sticky.onPhysical(key, pressed);
    }

    @Inject(method = "setKeyPressed", at = @At("TAIL"))
    private static void stickykeys$after(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        Sticky.reapply();
    }
}
