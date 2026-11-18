package com.example.powderassist.mixin;

import com.example.powderassist.Chests;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the chest solver about particles the server spawns (runs on the game thread). */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ParticleMixin {
    @Inject(method = "onParticle", at = @At("TAIL"))
    private void powderassist$onParticle(ParticleS2CPacket packet, CallbackInfo ci) {
        Chests.onParticle(packet.getParameters(), packet.getX(), packet.getY(), packet.getZ());
    }
}
