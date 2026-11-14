package com.example.crosshairaim;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Crosshair Aim (Fabric, Minecraft 1.21.10)
 *
 *   /aim <x> <y>   turn so your crosshair points exactly at yaw x, pitch y
 *   /aim           show the direction you're facing right now
 *
 * x = yaw   (left/right, same as F3 "Facing"; -180 to 180, other values wrap)
 * y = pitch (up/down, -90 = straight up, 90 = straight down)
 */
public class CrosshairAimClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("aim")
                        .executes(ctx -> showCurrent(ctx.getSource()))
                        .then(argument("x", FloatArgumentType.floatArg())
                                .then(argument("y", FloatArgumentType.floatArg(-90f, 90f))
                                        .executes(ctx -> aim(ctx.getSource(),
                                                FloatArgumentType.getFloat(ctx, "x"),
                                                FloatArgumentType.getFloat(ctx, "y")))))));
    }

    private static int aim(FabricClientCommandSource source, float x, float y) {
        ClientPlayerEntity player = source.getPlayer();
        float yaw = MathHelper.wrapDegrees(x);
        float pitch = MathHelper.clamp(y, -90f, 90f);

        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);

        source.sendFeedback(Text.literal("Aimed at ").formatted(Formatting.YELLOW)
                .append(Text.literal(fmt(yaw) + " / " + fmt(pitch)).formatted(Formatting.WHITE)));
        return 1;
    }

    private static int showCurrent(FabricClientCommandSource source) {
        ClientPlayerEntity player = source.getPlayer();
        source.sendFeedback(Text.literal("Facing ").formatted(Formatting.YELLOW)
                .append(Text.literal(fmt(MathHelper.wrapDegrees(player.getYaw())) + " / "
                        + fmt(player.getPitch())).formatted(Formatting.WHITE))
                .append(Text.literal("   (x = yaw, y = pitch)").formatted(Formatting.GRAY)));
        return 1;
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }
}
