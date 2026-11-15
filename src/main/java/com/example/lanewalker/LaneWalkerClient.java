package com.example.lanewalker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Lane Walker (Fabric, Minecraft 1.21.10)
 *
 * Walks you one way until you hit a wall (or go a set number of blocks),
 * then switches and walks the other way, back and forth until you stop it.
 *
 *   K                                 start / stop (uses the last directions, default left -> right)
 *   /walk <first> <second>            start: walk <first> until a wall, then <second>, and repeat
 *   /walk <first> <second> <blocks>   same, but switch after <blocks> blocks (or at a wall)
 *   /walk stop                        stop
 *   /walk repeat <times>              stop by itself after that many round trips
 *   /walk repeat forever              keep going until you stop it (default)
 *
 * Directions: forward, back, left, right (relative to where you're looking).
 */
public class LaneWalkerClient implements ClientModInitializer {

    enum Dir { FORWARD, BACK, LEFT, RIGHT }

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lanewalker", "main"));

    /** Ticks to wait after switching before a wall can trigger the next switch. */
    private static final int SWITCH_COOLDOWN = 6;

    private static KeyBinding toggleKey;

    private static boolean running = false;
    private static Dir first = Dir.LEFT;
    private static Dir second = Dir.RIGHT;
    private static int maxBlocks = 0;          // 0 = only switch at walls
    private static int repeats = 0;            // round trips before stopping, 0 = forever
    private static int tripsDone = 0;
    private static boolean onFirst = true;
    private static int cooldown = 0;
    private static Vec3d legStart = null;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lanewalker.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));

        ClientTickEvents.START_CLIENT_TICK.register(LaneWalkerClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> walk = literal("walk");
            walk.then(literal("repeat")
                    .then(literal("forever").executes(ctx -> setRepeats(0)))
                    .then(argument("times", IntegerArgumentType.integer(1, 100000))
                            .executes(ctx -> setRepeats(IntegerArgumentType.getInteger(ctx, "times")))));
            walk.then(literal("stop").executes(ctx -> {
                stop(MinecraftClient.getInstance(), "stopped");
                return 1;
            }));
            for (Dir a : Dir.values()) {
                LiteralArgumentBuilder<FabricClientCommandSource> aNode = literal(name(a));
                for (Dir b : Dir.values()) {
                    if (a == b) continue;
                    aNode.then(literal(name(b))
                            .executes(ctx -> start(a, b, 0))
                            .then(argument("blocks", IntegerArgumentType.integer(1, 10000))
                                    .executes(ctx -> start(a, b, IntegerArgumentType.getInteger(ctx, "blocks")))));
                }
                walk.then(aNode);
            }
            dispatcher.register(walk);
        });
    }

    private static int setRepeats(int times) {
        repeats = times;
        say(MinecraftClient.getInstance(), Text.literal("Lane Walker: repeat ").formatted(Formatting.YELLOW)
                .append(Text.literal(repeatText()).formatted(Formatting.WHITE)));
        return 1;
    }

    private static String repeatText() {
        return repeats == 0 ? "forever" : repeats + (repeats == 1 ? " time" : " times");
    }

    private static int start(Dir a, Dir b, int blocks) {
        first = a;
        second = b;
        maxBlocks = blocks;
        begin(MinecraftClient.getInstance());
        return 1;
    }

    private static void begin(MinecraftClient client) {
        if (client.player == null) return;
        running = true;
        onFirst = true;
        tripsDone = 0;
        cooldown = SWITCH_COOLDOWN;
        legStart = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        say(client, Text.literal("Lane Walker: ").formatted(Formatting.YELLOW)
                .append(Text.literal(name(first) + " <-> " + name(second)
                        + (maxBlocks > 0 ? " every " + maxBlocks + " blocks" : " (switch at walls)")
                        + ", repeat " + repeatText())
                        .formatted(Formatting.GREEN))
                .append(Text.literal("  K to stop").formatted(Formatting.GRAY)));
    }

    private static void stop(MinecraftClient client, String why) {
        if (!running) return;
        running = false;
        KeyBinding.updatePressedStates(); // hand movement keys back to the real keyboard
        say(client, Text.literal("Lane Walker: " + why).formatted(Formatting.YELLOW));
    }

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            running = false;
            return;
        }

        while (toggleKey.wasPressed()) {
            if (running) stop(client, "stopped");
            else begin(client);
        }
        if (!running) return;
        if (player.isDead()) {
            stop(client, "stopped (you died)");
            return;
        }
        if (client.currentScreen != null) return; // don't walk while a menu is open

        Dir current = onFirst ? first : second;

        if (cooldown > 0) {
            cooldown--;
        } else {
            boolean hitWall = player.horizontalCollision;
            boolean farEnough = false;
            if (maxBlocks > 0 && legStart != null) {
                Vec3d now = new Vec3d(player.getX(), player.getY(), player.getZ());
                double dx = now.x - legStart.x, dz = now.z - legStart.z;
                farEnough = dx * dx + dz * dz >= (double) maxBlocks * maxBlocks;
            }
            if (hitWall || farEnough) {
                if (!onFirst) {           // finished a full round trip
                    tripsDone++;
                    if (repeats > 0 && tripsDone >= repeats) {
                        stop(client, "done (" + tripsDone + (tripsDone == 1 ? " round trip)" : " round trips)"));
                        return;
                    }
                }
                onFirst = !onFirst;
                current = onFirst ? first : second;
                cooldown = SWITCH_COOLDOWN;
                legStart = new Vec3d(player.getX(), player.getY(), player.getZ());
                say(client, Text.literal("Lane Walker: now going ").formatted(Formatting.YELLOW)
                        .append(Text.literal(name(current)).formatted(Formatting.WHITE))
                        .append(Text.literal(repeats > 0
                                ? "  (trip " + (tripsDone + 1) + "/" + repeats + ")"
                                : "  (trip " + (tripsDone + 1) + ")").formatted(Formatting.GRAY)));
            }
        }

        GameOptions o = client.options;
        o.forwardKey.setPressed(current == Dir.FORWARD);
        o.backKey.setPressed(current == Dir.BACK);
        o.leftKey.setPressed(current == Dir.LEFT);
        o.rightKey.setPressed(current == Dir.RIGHT);
    }

    private static String name(Dir d) {
        return d.name().toLowerCase();
    }

    private static void say(MinecraftClient client, Text msg) {
        client.inGameHud.setOverlayMessage(msg, false);
    }
}
