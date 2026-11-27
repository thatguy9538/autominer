package com.example.ghostbot.mixin;

import com.example.ghostbot.Glide;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the camera glide once per frame, before the frame is drawn. */
@Mixin(MinecraftClient.class)
public abstract class FrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void ghostbot$frame(boolean tick, CallbackInfo ci) {
        Glide.frame();
    }
}
