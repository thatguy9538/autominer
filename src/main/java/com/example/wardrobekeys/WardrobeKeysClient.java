package com.example.wardrobekeys;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wardrobe Keys: one key per wardrobe slot. Press it and the mod opens /wardrobe, turns to the
 * right page, clicks that slot's equip button and closes the menu. Also /wd <slot>.
 */
public class WardrobeKeysClient implements ClientModInitializer {

    public static final String MOD_ID = "wardrobekeys";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    /** Slots per wardrobe page, and how many slots get a key. */
    private static final int PER_PAGE = 9, SLOTS = 18;

    private static final KeyBinding[] keys = new KeyBinding[SLOTS];

    // settings (config/wardrobekeys.txt)
    private static boolean closeAfter = true;
    private static String command = "wardrobe";

    // the swap in progress
    private enum Phase { IDLE, OPENING, TURNING, CLICKED }

    private static Phase phase = Phase.IDLE;
    private static int target = 0, timer = 0, settle = 0;
    private static String lastPageSig = "";

    private static final Pattern SLOT_NAME = Pattern.compile("slot\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");

    @Override
    public void onInitializeClient() {
        loadConfig();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        for (int i = 0; i < SLOTS; i++) {
            // slots 1-9 default to nothing so they don't clash with your hotbar; set them in Controls
            keys[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.wardrobekeys.slot" + (i + 1),
                    InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        }
        ClientTickEvents.END_CLIENT_TICK.register(WardrobeKeysClient::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            for (String root : new String[]{"wd", "wardrobekeys"}) {
                dispatcher.register(ClientCommandManager.literal(root)
                        .then(ClientCommandManager.argument("slot", IntegerArgumentType.integer(1, SLOTS))
                                .executes(c -> {
                                    start(MinecraftClient.getInstance(), IntegerArgumentType.getInteger(c, "slot"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("close").executes(c -> {
                            closeAfter = !closeAfter;
                            saveConfig();
                            say(MinecraftClient.getInstance(), "Close the wardrobe after swapping: " + (closeAfter ? "ON" : "OFF"));
                            return 1;
                        }))
                        .executes(c -> {
                            say(MinecraftClient.getInstance(), "/wd <1-" + SLOTS + "> equips a wardrobe slot. /wd close turns"
                                    + " closing the menu afterwards on/off (now " + (closeAfter ? "ON" : "OFF")
                                    + "). Set keys in Options > Controls > Wardrobe Keys.");
                            return 1;
                        }));
            }
        });
    }

    private static void tick(MinecraftClient client) {
        for (int i = 0; i < SLOTS; i++) {
            while (keys[i].wasPressed()) start(client, i + 1);
        }
        if (phase == Phase.IDLE || client.player == null) return;
        if (--timer <= 0) {
            fail(client, phase == Phase.OPENING ? "The wardrobe didn't open." : "The wardrobe didn't respond.");
            return;
        }
        HandledScreen<?> hs = wardrobeScreen(client);
        switch (phase) {
            case OPENING, TURNING -> {
                if (hs == null) return;
                String sig = pageSig(client);
                if (sig.isEmpty()) return;                      // items not here yet
                if (phase == Phase.TURNING && sig.equals(lastPageSig)) return;   // still the old page
                if (++settle < 3) return;                       // let the whole page arrive
                handlePage(client, hs, sig);
            }
            case CLICKED -> {
                if (++settle < 4) return;
                if (closeAfter && hs != null) client.player.closeHandledScreen();
                phase = Phase.IDLE;
            }
            default -> { }
        }
    }

    private static void start(MinecraftClient client, int slot) {
        if (client.player == null) return;
        if (phase != Phase.IDLE) {
            say(client, "Still swapping, wait a moment.");
            return;
        }
        target = slot;
        settle = 0;
        timer = 60;
        lastPageSig = "";
        if (wardrobeScreen(client) == null) {
            if (client.currentScreen != null) client.player.closeHandledScreen();
            client.player.networkHandler.sendChatCommand(command);
        }
        phase = Phase.OPENING;
    }

    private static void handlePage(MinecraftClient client, HandledScreen<?> hs, String sig) {
        int page = 1, pages = 1;
        Matcher pm = PAGE.matcher(title(hs));
        if (pm.find()) {
            page = Integer.parseInt(pm.group(1));
            pages = Integer.parseInt(pm.group(2));
        }
        int wantPage = (target - 1) / PER_PAGE + 1;
        Slot button = findSlotButton(client, target);
        if (button == null && page != wantPage) {
            // turn the page with the arrow
            Slot arrow = findByName(client, page < wantPage ? "next page" : "previous page");
            if (arrow == null || wantPage > pages) {
                fail(client, "Your wardrobe has no slot " + target + ".");
                return;
            }
            click(client, arrow);
            lastPageSig = sig;
            settle = 0;
            timer = 60;
            phase = Phase.TURNING;
            return;
        }
        if (button == null) {
            dump(client);
            fail(client, "Couldn't find slot " + target + "'s button in the wardrobe (see config/wardrobekeys-dump.txt).");
            return;
        }
        String all = (Formatting.strip(button.getStack().getName().getString()) + " " + lore(button.getStack()))
                .toLowerCase(Locale.ROOT);
        if (all.contains("locked")) {
            fail(client, "Wardrobe slot " + target + " is locked.");
            return;
        }
        if (all.contains("empty") && !all.contains("click to equip")) {
            fail(client, "Wardrobe slot " + target + " is empty.");
            return;
        }
        if (all.contains("equipped") && !all.contains("click to equip")) {
            say(client, "Slot " + target + " is already on.");
            finishNoClick(client);
            return;
        }
        click(client, button);
        phase = Phase.CLICKED;
        settle = 0;
        timer = 40;
    }

    /** The equip button for a slot: an item named "Slot N: ..." (any page), else its usual place on Hypixel. */
    private static Slot findSlotButton(MinecraftClient client, int n) {
        for (Slot s : container(client)) {
            String name = Formatting.strip(s.getStack().getName().getString());
            if (name == null) continue;
            Matcher m = SLOT_NAME.matcher(name);
            if (m.find() && Integer.parseInt(m.group(1)) == n) return s;
        }
        return null;
    }

    private static Slot findByName(MinecraftClient client, String words) {
        for (Slot s : container(client)) {
            String name = Formatting.strip(s.getStack().getName().getString());
            if (name != null && name.toLowerCase(Locale.ROOT).contains(words)) return s;
        }
        return null;
    }

    private static java.util.List<Slot> container(MinecraftClient client) {
        java.util.List<Slot> out = new java.util.ArrayList<>();
        for (Slot s : client.player.currentScreenHandler.slots) {
            if (s.inventory != client.player.getInventory() && s.hasStack()) out.add(s);
        }
        return out;
    }

    private static String pageSig(MinecraftClient client) {
        StringBuilder sb = new StringBuilder();
        for (Slot s : container(client)) sb.append(s.id).append(s.getStack().getName().getString()).append('|');
        return sb.toString();
    }

    private static HandledScreen<?> wardrobeScreen(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> hs && title(hs).toLowerCase(Locale.ROOT).contains("wardrobe")) {
            return hs;
        }
        return null;
    }

    private static String title(HandledScreen<?> hs) {
        String t = Formatting.strip(hs.getTitle().getString());
        return t == null ? "" : t;
    }

    private static String lore(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text t : lore.lines()) sb.append(Formatting.strip(t.getString())).append(' ');
        return sb.toString();
    }

    private static void click(MinecraftClient client, Slot slot) {
        client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slot.id, 0,
                SlotActionType.PICKUP, client.player);
    }

    private static void finishNoClick(MinecraftClient client) {
        if (closeAfter && wardrobeScreen(client) != null) client.player.closeHandledScreen();
        phase = Phase.IDLE;
    }

    private static void fail(MinecraftClient client, String why) {
        phase = Phase.IDLE;
        say(client, why);
    }

    static void say(MinecraftClient client, String msg) {
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("[Wardrobe] ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(msg).formatted(Formatting.WHITE)), false);
    }

    /** Writes the wardrobe's items to a file, so the button search can be fixed if Fakepixel names them differently. */
    private static void dump(MinecraftClient client) {
        StringBuilder sb = new StringBuilder();
        if (client.currentScreen instanceof HandledScreen<?> hs) sb.append("Title: ").append(title(hs)).append('\n');
        for (Slot s : container(client)) {
            sb.append('[').append(s.id).append("] ").append(Formatting.strip(s.getStack().getName().getString()))
                    .append("  |  ").append(lore(s.getStack())).append('\n');
        }
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("wardrobekeys-dump.txt"), sb.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Couldn't write the wardrobe dump", e);
        }
    }

    // ------------------------------------------------------------------ config

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("wardrobekeys.txt");
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(configFile())) {
                saveConfig();
                return;
            }
            for (String line : Files.readAllLines(configFile(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("closeAfter")) closeAfter = v.equals("true");
                if (k.equals("command") && !v.isEmpty()) command = v.replaceFirst("^/", "");
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read the config", e);
        }
    }

    private static void saveConfig() {
        try {
            Files.writeString(configFile(), "# Wardrobe Keys\n# close the wardrobe after swapping\ncloseAfter=" + closeAfter
                    + "\n# command that opens the wardrobe (without /)\ncommand=" + command + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Couldn't save the config", e);
        }
    }
}
