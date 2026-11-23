package com.example.multibind.mixin;

import com.example.multibind.MultiBind;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets extra keys drive a binding: hooks every real key / mouse press, release and re-sync. */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Inject(method = "setKeyPressed", at = @At("TAIL"))
    private static void multibind$setKeyPressed(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        MultiBind.onKey(key, pressed);
    }

    @Inject(method = "onKeyPressed", at = @At("TAIL"))
    private static void multibind$onKeyPressed(InputUtil.Key key, CallbackInfo ci) {
        MultiBind.onClick(key);
    }

    @Inject(method = "updatePressedStates", at = @At("TAIL"))
    private static void multibind$updatePressedStates(CallbackInfo ci) {
        MultiBind.resync();
    }
}
