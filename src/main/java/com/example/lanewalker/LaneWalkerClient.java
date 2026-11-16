package com.example.lanewalker;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Lane Walker (Fabric, Minecraft 1.21.10)
 *
 * Build walking scripts out of Scratch-style blocks, then run them.
 * Each script starts with "when [key] pressed"; that key runs / stops it.
 *
 *   B            open the block editor
 *   /walk edit   open the block editor
 *   /walk run    run the first script
 *   /walk stop   stop
 */
public class LaneWalkerClient implements ClientModInitializer {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lanewalker", "main"));

    /** Ticks to wait after a walk step starts before a wall can end it. */
    private static final int WALL_COOLDOWN = 6;

    private static KeyBinding editorKey;

    /** All scripts. */
    public static Workspace workspace;
    /** The script that is running (or last ran). */
    public static Program program;
    /** Script keys that were held down last tick, to spot new presses. */
    private static final java.util.Set<Integer> keysDown = new java.util.HashSet<>();
    private static Path configFile;

    private static boolean running = false;
    private static int stepIndex = 0;
    private static int tripsDone = 0;
    private static int cooldown = 0;
    private static int waitTicks = 0;
    private static Vec3d legStart = null;
    private static boolean wasAtStopSpot = false;
    /** For "if" blocks: did each block (by id) complete the last time it ran? */
    private static final java.util.Map<Integer, Boolean> outcomes = new java.util.HashMap<>();
    private static boolean controllingVertical = false;   // jump / sneak keys held by "go to"
    private static double goBest = Double.MAX_VALUE;      // closest "go to" has got so far
    private static int goStuck = 0;                        // ticks without getting closer
    private static final int GO_STUCK_LIMIT = 200;         // 10 seconds
    private static final java.util.Random RANDOM = new java.util.Random();

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("lanewalker.txt");
        workspace = Workspace.load(configFile);
        program = workspace.scripts.get(0);

        editorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lanewalker.editor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));

        ClientTickEvents.START_CLIENT_TICK.register(LaneWalkerClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> walk = literal("walk");
            walk.then(literal("stop").executes(ctx -> {
                stop(MinecraftClient.getInstance(), "stopped");
                return 1;
            }));
            walk.then(literal("run").executes(ctx -> {
                for (Program p : workspace.scripts) {
                    if (!p.steps.isEmpty()) {
                        start(MinecraftClient.getInstance(), p);
                        break;
                    }
                }
                return 1;
            }));
            walk.then(literal("edit").executes(ctx -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.send(() -> openEditor(client));
                return 1;
            }));
            dispatcher.register(walk);
        });
    }

    /** Display name for a GLFW key code, e.g. "K" or "Left Shift". */
    public static String keyName(int key) {
        if (key < 0) return "?";
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }

    public static void saveProgram() {
        workspace.save(configFile);
    }

    public static void openEditor(MinecraftClient client) {
        stop(client, "stopped (editing)");
        client.setScreen(new BlockEditorScreen());
    }

    public static void start(MinecraftClient client, Program script) {
        if (client.player == null || script == null) return;
        if (script.steps.isEmpty()) {
            say(client, Text.literal("Lane Walker: the [" + keyName(script.key)
                    + "] script is empty. Press B to add blocks.").formatted(Formatting.RED));
            return;
        }
        if (running) {                       // switching scripts: let go of the old one's keys first
            running = false;
            controllingVertical = false;
            KeyBinding.updatePressedStates();
        }
        program = script;
        running = true;
        tripsDone = 0;
        outcomes.clear();
        wasAtStopSpot = atStopSpot(client.player) != null;   // only stop when you *arrive* at a spot
        startStep(client, 0);
    }

    public static void stop(MinecraftClient client, String why) {
        if (!running) return;
        running = false;
        controllingVertical = false;
        KeyBinding.updatePressedStates(); // hand movement keys back to the real keyboard
        say(client, Text.literal("Lane Walker: " + why).formatted(Formatting.YELLOW));
    }

    private static void startStep(MinecraftClient client, int index) {
        stepIndex = index;
        Program.Step step = program.steps.get(index);
        cooldown = WALL_COOLDOWN;
        goBest = Double.MAX_VALUE;
        goStuck = 0;
        String desc = step.describe();
        if (step.kind == Program.Kind.WAIT_RANDOM) {
            int lo = Math.min(step.amount, step.amount2), hi = Math.max(step.amount, step.amount2);
            int t = lo + RANDOM.nextInt(hi - lo + 1);          // tenths of a second
            waitTicks = Math.max(1, t * 2);
            desc = "random wait: " + Program.tenths(t) + " s";
        } else {
            waitTicks = step.amount * 20;
        }
        ClientPlayerEntity p = client.player;
        legStart = new Vec3d(p.getX(), p.getY(), p.getZ());
        String trip = program.repeats > 0
                ? "  (trip " + (tripsDone + 1) + "/" + program.repeats + ")"
                : "  (trip " + (tripsDone + 1) + ")";
        say(client, Text.literal("Lane Walker: ").formatted(Formatting.YELLOW)
                .append(Text.literal(desc).formatted(Formatting.WHITE))
                .append(Text.literal(trip).formatted(Formatting.GRAY)));
    }

    /** Record whether the current block completed, then move on. */
    private static boolean finish(MinecraftClient client, boolean completed) {
        outcomes.put(program.steps.get(stepIndex).id, completed);
        return advance(client);
    }

    /** Move on to the next block; returns false if the program finished. */
    private static boolean advance(MinecraftClient client) {
        int next = stepIndex + 1;
        if (next >= program.steps.size()) {
            tripsDone++;
            if (program.repeats > 0 && tripsDone >= program.repeats) {
                stop(client, "done (" + tripsDone + (tripsDone == 1 ? " round trip)" : " round trips)"));
                return false;
            }
            next = 0;
        }
        startStep(client, next);
        return true;
    }

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            running = false;
            return;
        }

        while (editorKey.wasPressed()) {
            if (client.currentScreen == null) openEditor(client);
        }
        // each script's "when [key] pressed" key runs it, or stops it if it's already running
        if (client.currentScreen == null) {
            long handle = client.getWindow().getHandle();
            for (Program script : workspace.scripts) {
                int key = script.key;
                if (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST) continue;
                boolean down = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
                if (down && !keysDown.contains(key)) {
                    if (running && program == script) stop(client, "stopped");
                    else start(client, script);
                }
            }
            keysDown.clear();
            for (Program script : workspace.scripts) {
                int key = script.key;
                if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
                        && GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS) keysDown.add(key);
            }
        } else {
            keysDown.clear();
        }
        if (!running) return;
        if (program.steps.isEmpty()) {
            stop(client, "stopped (empty program)");
            return;
        }
        if (player.isDead()) {
            stop(client, "stopped (you died)");
            return;
        }
        if (client.currentScreen != null) return; // don't walk while a menu is open
        if (stepIndex >= program.steps.size()) stepIndex = 0;

        // "stop at x z" blocks are checked every tick, wherever they are in the script
        Program.Step spot = atStopSpot(player);
        if (spot != null && !wasAtStopSpot) {
            stop(client, "reached x " + spot.amount + " z " + spot.amount2 + ", stopped");
            return;
        }
        wasAtStopSpot = spot != null;

        Program.Step step = program.steps.get(stepIndex);
        Program.Dir walkDir = null;
        boolean goToActive = false, wantJump = false, wantSneak = false;

        switch (step.kind) {
            case GO_TO -> {
                double dx = step.amount + 0.5 - player.getX();
                double dy = step.amount2 - player.getY();
                double dz = step.amount3 + 0.5 - player.getZ();
                double h = Math.sqrt(dx * dx + dz * dz);
                boolean canFly = player.getAbilities().allowFlying;
                boolean arrived = h < 0.6 && (!canFly || Math.abs(dy) < 0.6);

                if (arrived) {
                    if (!finish(client, true)) return;
                    Program.Step now = program.steps.get(stepIndex);
                    walkDir = now.kind.isWalk() ? now.dir : null;
                    break;
                }

                double dist = canFly ? Math.sqrt(h * h + dy * dy) : h;
                if (dist < goBest - 0.05) {
                    goBest = dist;
                    goStuck = 0;
                } else if (++goStuck > GO_STUCK_LIMIT) {
                    stop(client, "couldn't get to " + step.amount + " " + step.amount2 + " " + step.amount3
                            + ", stopped");
                    return;
                }

                // only fly when the game lets you (creative, or a server that allows flying)
                if (canFly && !player.getAbilities().flying) {
                    player.getAbilities().flying = true;
                    player.sendAbilitiesUpdate();
                }

                if (h >= 0.6) {
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    player.setYaw(yaw);
                    player.setHeadYaw(yaw);
                    walkDir = Program.Dir.FORWARD;
                }
                goToActive = true;
                if (player.getAbilities().flying) {
                    wantJump = dy > 0.4;     // fly up
                    wantSneak = dy < -0.4;   // fly down
                } else {
                    wantJump = player.horizontalCollision && player.isOnGround();   // hop up steps
                }
            }
            case IF -> {
                Program.Step linked = step.link >= 0 ? program.byId(step.link) : null;
                boolean cond = linked != null && outcomes.getOrDefault(linked.id, false);
                if (!finish(client, true)) return;
                if (!cond) {                  // condition false: skip the block under the "if"
                    outcomes.put(program.steps.get(stepIndex).id, false);
                    if (!advance(client)) return;
                }
                Program.Step now = program.steps.get(stepIndex);
                walkDir = now.kind.isWalk() ? now.dir : null;
            }
            case STOP_AT -> {                 // nothing to do in order; it's checked every tick above
                if (!finish(client, true)) return;
                Program.Step now = program.steps.get(stepIndex);
                walkDir = now.kind.isWalk() ? now.dir : null;
            }
            case SET_ANGLE -> {
                float yaw = MathHelper.wrapDegrees((float) step.amount);
                float pitch = MathHelper.clamp((float) step.amount2, -90f, 90f);
                player.setYaw(yaw);
                player.setPitch(pitch);
                player.setHeadYaw(yaw);
                if (!finish(client, true)) return;
                Program.Step now = program.steps.get(stepIndex);
                walkDir = now.kind.isWalk() ? now.dir : null;
            }
            case WAIT, WAIT_RANDOM -> {
                if (--waitTicks <= 0) {
                    if (!finish(client, true)) return;
                }
            }
            case WALK_WALL, WALK_BLOCKS -> {
                walkDir = step.dir;
                if (cooldown > 0) {
                    cooldown--;
                } else {
                    boolean wall = player.horizontalCollision;
                    boolean distance = false;
                    if (step.kind == Program.Kind.WALK_BLOCKS && legStart != null) {
                        double dx = player.getX() - legStart.x, dz = player.getZ() - legStart.z;
                        distance = dx * dx + dz * dz >= (double) step.amount * step.amount;
                    }
                    if (wall || distance) {
                        // "until wall" completes at a wall; "N blocks" only completes if it went the full distance
                        boolean completed = step.kind == Program.Kind.WALK_WALL || distance;
                        if (!finish(client, completed)) return;
                        Program.Step now = program.steps.get(stepIndex);
                        walkDir = now.kind.isWalk() ? now.dir : null;
                    }
                }
            }
        }

        GameOptions o = client.options;
        if (controllingVertical && !goToActive) KeyBinding.updatePressedStates();   // give jump/sneak back
        if (goToActive) {
            o.jumpKey.setPressed(wantJump);
            o.sneakKey.setPressed(wantSneak);
        }
        controllingVertical = goToActive;
        o.forwardKey.setPressed(walkDir == Program.Dir.FORWARD);
        o.backKey.setPressed(walkDir == Program.Dir.BACK);
        o.leftKey.setPressed(walkDir == Program.Dir.LEFT);
        o.rightKey.setPressed(walkDir == Program.Dir.RIGHT);
    }

    /** The "stop at" block whose spot the player is standing on, or null. */
    private static Program.Step atStopSpot(ClientPlayerEntity player) {
        int bx = MathHelper.floor(player.getX()), bz = MathHelper.floor(player.getZ());
        for (Program.Step s : program.steps) {
            if (s.kind == Program.Kind.STOP_AT && s.amount == bx && s.amount2 == bz) return s;
        }
        return null;
    }

    static void say(MinecraftClient client, Text msg) {
        client.inGameHud.setOverlayMessage(msg, false);
    }
}
