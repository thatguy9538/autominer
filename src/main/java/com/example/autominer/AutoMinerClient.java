package com.example.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Auto Miner (Fabric, Minecraft 1.21.10)
 *
 * Pick one or more block types, then the mod automatically mines every block
 * of those types that is within your reach and that you can see.
 *
 *   G = add / remove the block you are looking at from the target list
 *   J = clear the target list
 *   H = turn auto-mining on / off
 *
 * Both keys can be changed in Options > Controls > Key Binds > Auto Miner.
 */
public class AutoMinerClient implements ClientModInitializer {

    public static final String MOD_ID = "autominer";

    // 1.21.9+ key binding categories are objects, not plain strings.
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    private static KeyBinding selectKey;
    private static KeyBinding toggleKey;
    private static KeyBinding clearKey;

    private static final Set<Block> targets = new LinkedHashSet<>();
    private static boolean enabled = false;
    private static BlockPos currentPos = null;
    private static Direction currentSide = Direction.UP;

    @Override
    public void onInitializeClient() {
        selectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));
        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.clear", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(AutoMinerClient::onTick);
    }

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            enabled = false;
            currentPos = null;
            return;
        }

        // --- Select target block ---
        while (selectKey.wasPressed()) {
            HitResult hit = client.crosshairTarget;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = bhr.getBlockPos();
                BlockState state = world.getBlockState(pos);
                if (state.getHardness(world, pos) < 0) {
                    say(client, Text.literal("That block can't be mined.").formatted(Formatting.RED));
                } else {
                    Block block = state.getBlock();
                    boolean added = targets.add(block);
                    if (!added) targets.remove(block);
                    if (currentPos != null) client.interactionManager.cancelBlockBreaking();
                    currentPos = null;
                    say(client, Text.literal(added ? "Added: " : "Removed: ")
                            .formatted(added ? Formatting.GREEN : Formatting.RED)
                            .append(block.getName().copy().formatted(Formatting.WHITE))
                            .append(Text.literal("  (" + targets.size() + " block type"
                                    + (targets.size() == 1 ? "" : "s") + ")").formatted(Formatting.GRAY)));
                }
            } else {
                say(client, Text.literal("Look at a block to select it.").formatted(Formatting.RED));
            }
        }

        // --- Clear list ---
        while (clearKey.wasPressed()) {
            targets.clear();
            if (currentPos != null) client.interactionManager.cancelBlockBreaking();
            currentPos = null;
            enabled = false;
            say(client, Text.literal("Auto Miner: target list cleared").formatted(Formatting.YELLOW));
        }

        // --- Toggle on/off ---
        while (toggleKey.wasPressed()) {
            if (targets.isEmpty()) {
                say(client, Text.literal("Select a block first (G).").formatted(Formatting.RED));
                continue;
            }
            enabled = !enabled;
            if (!enabled && currentPos != null) client.interactionManager.cancelBlockBreaking();
            currentPos = null;
            say(client, Text.literal("Auto Miner: ").formatted(Formatting.YELLOW)
                    .append(enabled
                            ? Text.literal("ON").formatted(Formatting.GREEN)
                            : Text.literal("OFF").formatted(Formatting.RED))
                    .append(Text.literal(" (" + targetNames() + ")").formatted(Formatting.GRAY)));
        }

        if (!enabled || targets.isEmpty()) return;
        if (client.currentScreen != null) return; // pause while a menu/inventory is open
        if (player.isSpectator()) return;

        Vec3d eye = player.getEyePos();

        // Keep mining the current block, or pick the next closest one.
        Direction visibleSide = currentPos == null ? null : visibleFace(client, world, eye, currentPos);
        if (visibleSide == null) {
            if (currentPos != null) client.interactionManager.cancelBlockBreaking();
            currentPos = findClosest(client, world, eye);
            if (currentPos == null) return; // nothing in reach
        } else {
            currentSide = visibleSide;
        }

        if (client.interactionManager.updateBlockBreakingProgress(currentPos, currentSide)) {
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Scan around the player for the nearest visible target block within reach. */
    private static BlockPos findClosest(MinecraftClient client, ClientWorld world, Vec3d eye) {
        ClientPlayerEntity player = client.player;
        int r = (int) Math.ceil(player.getBlockInteractionRange()) + 1;
        BlockPos center = BlockPos.ofFloored(eye);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos best = null;
        Direction bestSide = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!isTarget(world.getBlockState(pos))) continue;
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (d >= bestDist) continue;
                    BlockPos candidate = pos.toImmutable();
                    Direction side = visibleFace(client, world, eye, candidate);
                    if (side != null) {
                        bestDist = d;
                        best = candidate;
                        bestSide = side;
                    }
                }
            }
        }
        if (best != null) currentSide = bestSide;
        return best;
    }

    /**
     * Returns the face of the block you can hit with an unobstructed line of
     * sight, or null if the block isn't the target, is out of reach, or is hidden
     * behind other blocks.
     */
    private static Direction visibleFace(MinecraftClient client, ClientWorld world, Vec3d eye, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        if (!isTarget(world.getBlockState(pos))) return null;
        if (!player.canInteractWithBlockAt(pos, 0.0)) return null; // same reach check the server uses

        Vec3d c = Vec3d.ofCenter(pos);
        // Try the block centre first, then the centre of each face.
        Vec3d[] points = new Vec3d[7];
        points[0] = c;
        int i = 1;
        for (Direction dir : Direction.values()) {
            points[i++] = c.add(dir.getOffsetX() * 0.49, dir.getOffsetY() * 0.49, dir.getOffsetZ() * 0.49);
        }

        for (Vec3d p : points) {
            BlockHitResult hit = world.raycast(new RaycastContext(
                    eye, p, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return hit.getSide();
            }
        }
        return null;
    }

    private static boolean isTarget(BlockState state) {
        return targets.contains(state.getBlock());
    }

    private static String targetNames() {
        StringBuilder sb = new StringBuilder();
        for (Block b : targets) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(b.getName().getString());
        }
        return sb.toString();
    }

    private static void say(MinecraftClient client, Text msg) {
        client.inGameHud.setOverlayMessage(msg, false); // shows above the hotbar
    }
}
