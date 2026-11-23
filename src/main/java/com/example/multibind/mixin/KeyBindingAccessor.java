package com.example.multibind.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Click count of a binding (what wasPressed() uses), so an extra key counts as a click too. */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int multibind$getTimesPressed();

    @Accessor("timesPressed")
    void multibind$setTimesPressed(int value);
}
