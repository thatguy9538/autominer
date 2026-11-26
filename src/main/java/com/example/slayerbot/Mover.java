package com.example.slayerbot;

import net.minecraft.client.MinecraftClient;

/** Something that gets the player somewhere: walking, or etherwarping. */
public interface Mover {
    Walker.Result tick(MinecraftClient client);

    void stop(MinecraftClient client);
}
