package com.example.autominer.mixin;

import com.example.autominer.AutoMinerClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells Auto Miner about particles the server spawns (runs on the game thread). */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onParticle", at = @At("TAIL"))
    private void autominer$onParticle(ParticleS2CPacket packet, CallbackInfo ci) {
        AutoMinerClient.onServerParticle(packet.getParameters(), packet.getX(), packet.getY(), packet.getZ());
    }
}
