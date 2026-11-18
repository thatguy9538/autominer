package com.example.autominer;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The jobs the commission bot does between commissions: refuelling the drill at
 * Jotraeline, selling a full inventory with /bz, and using Mining Speed Boost.
 * Also watches chat for "Commission Complete!", an empty drill and the boost being ready.
 */
public final class Upkeep {

    private Upkeep() {
    }

    public static boolean autoRefuel = true, autoSell = true, autoBoost = true;

    /** Refuel when the drill's lore says it has less fuel than this. */
    private static final int LOW_FUEL = 150;
    /** Fuel items, best first. */
    private static final List<String> FUELS = List.of("volta", "oil barrel", "biofuel", "goblin egg",
            "enchanted block of coal", "enchanted coal", "enchanted charcoal", "block of coal", "coal", "charcoal");
    private static final Pattern FUEL_LINE = Pattern.compile("fuel:\\s*([0-9,.]+)([km]?)\\s*/", Pattern.CASE_INSENSITIVE);

    private enum Phase { NONE, FUEL_GO, FUEL_OPEN, FUEL_MENU, SELL_OPEN, SELL_MENU, SELL_CONFIRM }

    private static Phase phase = Phase.NONE;
    private static int timer = 0, step = 0, tries = 0;
    private static Mover mover = null;
    private static long tick = 0;
    private static boolean needFuel = false;
    private static boolean boostReady = true;
    private static long nextBoostTry = 0, fuelBlockedUntil = 0, sellBlockedUntil = 0;
    /** Hotbar slot the drill lives in (-1 = unknown). */
    private static int drillHotbar = -1;
    /** Set by chat when a commission finishes; the bot claims as soon as it sees it. */
    public static boolean commissionComplete = false;

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChat(message);
        });
    }

    private static void onChat(Text message) {
        String s = Formatting.strip(message.getString());
        if (s == null) return;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("commission complete")) commissionComplete = true;
        if (l.contains("is empty! refuel it") || l.contains("out of fuel") || l.contains("has no fuel")) needFuel = true;
        if (l.contains("mining speed boost is now available")) boostReady = true;
        if (l.contains("you used your mining speed boost")) boostReady = false;
        if (l.contains("ability is on cooldown") || l.contains("this ability is on cooldown")) {
            boostReady = false;
            nextBoostTry = tick + 600;                   // try again in 30 s in case we missed the "available" line
        }
    }

    /** Called once every client tick. */
    public static void clock() {
        tick++;
    }

    public static boolean busy() {
        return phase != Phase.NONE;
    }

    /** Is this our menu (so the bot shouldn't pause for it)? */
    public static boolean ownsScreen() {
        return phase == Phase.FUEL_OPEN || phase == Phase.FUEL_MENU || phase == Phase.SELL_OPEN
                || phase == Phase.SELL_MENU || phase == Phase.SELL_CONFIRM;
    }

    public static void reset(MinecraftClient client) {
        if (mover != null) mover.stop(client);
        mover = null;
        phase = Phase.NONE;
    }

    // ------------------------------------------------------------------ starting jobs

    /** Starts a refuel or sell trip if one is needed. Returns true if it did. */
    public static boolean maybeStart(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;
        rememberDrill(player);
        if (autoRefuel && tick >= fuelBlockedUntil && (needFuel || lowFuel(player))) {
            BlockPos npc = CommissionBot.locationOf("Refuel NPC");
            if (npc != null) {
                mover = CommissionBot.useEtherwarp ? EtherTravel.to(npc, 3.0) : Walker.to(npc, 3.0);
                phase = Phase.FUEL_GO;
                tries = 0;
                CommissionBot.status = "Drill is low on fuel, going to Jotraeline...";
                return true;
            }
        }
        if (autoSell && tick >= sellBlockedUntil && inventoryFull(player)) {
            phase = Phase.SELL_OPEN;
            timer = 0;
            step = 0;
            CommissionBot.status = "Inventory full, selling with /bz...";
            return true;
        }
        return false;
    }

    /** While mining: right-click the drill when Mining Speed Boost is ready. */
    public static void tryBoost(MinecraftClient client) {
        if (!autoBoost || !boostReady || tick < nextBoostTry) return;
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null) return;
        if (!isDrill(player.getMainHandStack())) return;
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        boostReady = false;                               // the chat tells us when it's back
        nextBoostTry = tick + 100;
        CommissionBot.status = "Used Mining Speed Boost";
    }

    // ------------------------------------------------------------------ running jobs

    /** Runs the current job. WALKING while busy, ARRIVED when done, FAILED if it gave up. */
    public static Walker.Result tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            reset(client);
            return Walker.Result.FAILED;
        }
        switch (phase) {
            case FUEL_GO -> {
                Walker.Result r = mover.tick(client);
                if (r == Walker.Result.WALKING) return r;
                mover.stop(client);
                mover = null;
                if (r == Walker.Result.FAILED) return giveUpFuel(player, "Couldn't walk to Jotraeline.");
                phase = Phase.FUEL_OPEN;
                timer = 10;
                step = 0;
            }
            case FUEL_OPEN -> {
                if (step == 0) {                          // right-click Jotraeline
                    if (--timer > 0) return Walker.Result.WALKING;
                    var npc = CommissionBot.findNpc(client.world, player,
                            List.of("Jotraeline", "Greatforge", "Drill Anvil", "Refuel"), 7);
                    if (npc == null) return giveUpFuel(player, "Can't find Jotraeline near the Refuel NPC spot.");
                    AutoMinerClient.aimAt(player, npc.getBoundingBox().getCenter());
                    client.interactionManager.interactEntity(player, npc, Hand.MAIN_HAND);
                    player.swingHand(Hand.MAIN_HAND);
                    step = 1;
                    timer = 60;
                } else if (client.currentScreen instanceof HandledScreen<?> hs && titleHas(hs, "anvil", "drill", "fuel")) {
                    phase = Phase.FUEL_MENU;
                    step = 0;
                    timer = 10;
                } else if (--timer <= 0) {
                    if (client.currentScreen != null) player.closeHandledScreen();
                    if (++tries >= 3) return giveUpFuel(player, "The refuel menu didn't open.");
                    step = 0;
                    timer = 20;
                }
            }
            case FUEL_MENU -> {
                if (--timer > 0) return Walker.Result.WALKING;
                timer = 6;
                return refuelStep(client, player);
            }
            case SELL_OPEN -> {
                if (step == 0) {
                    player.networkHandler.sendChatCommand("bz");
                    step = 1;
                    timer = 60;
                } else if (client.currentScreen instanceof HandledScreen<?>) {
                    phase = Phase.SELL_MENU;
                    timer = 12;
                } else if (--timer <= 0) {
                    return giveUpSell(player, "/bz didn't open a menu.");
                }
            }
            case SELL_MENU -> {
                if (--timer > 0) return Walker.Result.WALKING;
                Slot sell = findContainerSlot(player, "sell inventory");
                if (sell == null) return giveUpSell(player, "No \"Sell Inventory\" button in the /bz menu.");
                if (loreHas(sell.getStack(), "nothing to sell", "don't have any")) {
                    return giveUpSell(player, "Nothing in your inventory can be sold at the Bazaar.");
                }
                click(client, player, sell, SlotActionType.PICKUP);
                phase = Phase.SELL_CONFIRM;
                timer = 50;
            }
            case SELL_CONFIRM -> {
                Slot yes = findContainerSlot(player, "selling whole inventory", "confirm", "yes");
                if (yes != null && timer < 44) {
                    click(client, player, yes, SlotActionType.PICKUP);
                    timer = 0;
                }
                if (--timer <= 0) {
                    if (client.currentScreen != null) player.closeHandledScreen();
                    phase = Phase.NONE;
                    if (inventoryFull(player)) {          // the rest can't be sold: don't keep trying
                        sellBlockedUntil = tick + 6000;
                        warn(player, "Sold what I could, but your inventory is still full. Make some room.");
                    }
                    CommissionBot.status = "Sold! Back to work.";
                    return Walker.Result.ARRIVED;
                }
            }
            default -> {
                return Walker.Result.ARRIVED;
            }
        }
        return Walker.Result.WALKING;
    }

    /** One click in the drill anvil every few ticks: drill in, fuel in, refuel, drill out. */
    private static Walker.Result refuelStep(MinecraftClient client, ClientPlayerEntity player) {
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            return giveUpFuel(player, "The refuel menu closed.");
        }
        ScreenHandler h = player.currentScreenHandler;
        switch (step) {
            case 0 -> {                                   // drill + fuel from your inventory into the anvil
                Slot drill = null, fuel = null;
                int fuelRank = Integer.MAX_VALUE;
                for (Slot s : h.slots) {
                    if (s.inventory != player.getInventory() || !s.hasStack()) continue;
                    ItemStack st = s.getStack();
                    if (isDrill(st)) {
                        if (drill == null || s.getIndex() == drillHotbar) drill = s;
                        continue;
                    }
                    int r = fuelRank(st);
                    if (r < fuelRank) {
                        fuelRank = r;
                        fuel = s;
                    }
                }
                if (drill == null) return giveUpFuel(player, "No drill in your inventory.");
                if (fuel == null) {
                    player.closeHandledScreen();
                    return giveUpFuel(player, "No drill fuel (Volta, Oil Barrel, Goblin Egg, coal...) in your inventory.");
                }
                if (drill.getIndex() < 9) drillHotbar = drill.getIndex();
                click(client, player, drill, SlotActionType.QUICK_MOVE);
                step = 1;
            }
            case 1 -> {
                Slot fuel = null;
                int fuelRank = Integer.MAX_VALUE;
                for (Slot s : h.slots) {
                    if (s.inventory != player.getInventory() || !s.hasStack() || isDrill(s.getStack())) continue;
                    int r = fuelRank(s.getStack());
                    if (r < fuelRank) {
                        fuelRank = r;
                        fuel = s;
                    }
                }
                if (fuel != null) click(client, player, fuel, SlotActionType.QUICK_MOVE);
                step = 2;
                timer = 8;
            }
            case 2 -> {                                   // press the refuel button
                Slot button = findContainerSlot(player, "refuel", "fuel drill", "combine", "anvil");
                if (button == null && h.slots.size() > 22 && h.slots.get(22).inventory != player.getInventory()) {
                    button = h.slots.get(22);             // where it is on Hypixel
                }
                if (button != null) click(client, player, button, SlotActionType.PICKUP);
                step = 3;
                timer = 12;
            }
            default -> {                                  // take the drill back, into its old hotbar slot
                Slot out = null;
                for (Slot s : h.slots) {
                    if (s.inventory == player.getInventory() || !s.hasStack() || !isDrill(s.getStack())) continue;
                    if (out == null || s.id == 13) out = s;
                }
                if (out != null && step < 7) {
                    boolean free = drillHotbar >= 0 && player.getInventory().getStack(drillHotbar).isEmpty();
                    if (free) {
                        client.interactionManager.clickSlot(h.syncId, out.id, drillHotbar, SlotActionType.SWAP, player);
                    } else {
                        click(client, player, out, SlotActionType.QUICK_MOVE);
                    }
                    step++;
                    timer = 8;
                    return Walker.Result.WALKING;
                }
                player.closeHandledScreen();
                if (drillHotbar >= 0) player.getInventory().setSelectedSlot(drillHotbar);
                phase = Phase.NONE;
                if (out != null) {
                    fuelBlockedUntil = tick + 6000;
                    warn(player, "Couldn't take the drill back out of the anvil. Check it!");
                    return Walker.Result.FAILED;
                }
                needFuel = lowFuel(player);
                if (needFuel) {                           // refuel didn't take: don't loop
                    needFuel = false;
                    fuelBlockedUntil = tick + 6000;
                    warn(player, "Refuelled, but the drill still looks low on fuel. Check your fuel items.");
                }
                CommissionBot.status = "Drill refuelled! Back to work.";
                return Walker.Result.ARRIVED;
            }
        }
        return Walker.Result.WALKING;
    }

    private static Walker.Result giveUpFuel(ClientPlayerEntity player, String why) {
        if (mover != null) mover.stop(MinecraftClient.getInstance());
        mover = null;
        phase = Phase.NONE;
        needFuel = false;
        fuelBlockedUntil = tick + 6000;                   // 5 minutes before trying again
        warn(player, "Refuel: " + why);
        return Walker.Result.FAILED;
    }

    private static Walker.Result giveUpSell(ClientPlayerEntity player, String why) {
        if (MinecraftClient.getInstance().currentScreen != null) player.closeHandledScreen();
        phase = Phase.NONE;
        sellBlockedUntil = tick + 6000;
        warn(player, "Sell: " + why);
        return Walker.Result.FAILED;
    }

    // ------------------------------------------------------------------ items

    public static boolean isDrill(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        String n = Formatting.strip(s.getName().getString());
        if (n != null && n.toLowerCase(Locale.ROOT).contains("drill")) return true;
        return fuelOf(s) >= 0;
    }

    /** The fuel left in a drill from its lore ("Fuel: 2,345/3k"), or -1. */
    private static int fuelOf(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            String t = Formatting.strip(line.getString());
            if (t == null) continue;
            Matcher m = FUEL_LINE.matcher(t);
            if (!m.find()) continue;
            try {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                if (m.group(2).equalsIgnoreCase("k")) v *= 1000;
                if (m.group(2).equalsIgnoreCase("m")) v *= 1_000_000;
                return (int) v;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static void rememberDrill(ClientPlayerEntity player) {
        int sel = player.getInventory().getSelectedSlot();
        if (isDrill(player.getInventory().getStack(sel))) drillHotbar = sel;
    }

    private static boolean lowFuel(ClientPlayerEntity player) {
        if (drillHotbar < 0) return false;
        int f = fuelOf(player.getInventory().getStack(drillHotbar));
        return f >= 0 && f < LOW_FUEL;
    }

    private static int fuelRank(ItemStack s) {
        String n = Formatting.strip(s.getName().getString());
        if (n == null) return Integer.MAX_VALUE;
        n = n.toLowerCase(Locale.ROOT);
        for (int i = 0; i < FUELS.size(); i++) if (n.contains(FUELS.get(i))) return i;
        return Integer.MAX_VALUE;
    }

    private static boolean inventoryFull(ClientPlayerEntity player) {
        for (int i = 0; i < 36; i++) if (player.getInventory().getStack(i).isEmpty()) return false;
        return true;
    }

    // ------------------------------------------------------------------ menus

    private static Slot findContainerSlot(ClientPlayerEntity player, String... words) {
        for (Slot s : player.currentScreenHandler.slots) {
            if (s.inventory == player.getInventory() || !s.hasStack()) continue;
            String n = Formatting.strip(s.getStack().getName().getString());
            if (n == null) continue;
            n = n.toLowerCase(Locale.ROOT);
            for (String w : words) if (n.contains(w)) return s;
        }
        return null;
    }

    private static boolean loreHas(ItemStack s, String... words) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            String t = Formatting.strip(line.getString());
            if (t == null) continue;
            t = t.toLowerCase(Locale.ROOT);
            for (String w : words) if (t.contains(w)) return true;
        }
        return false;
    }

    private static boolean titleHas(HandledScreen<?> hs, String... words) {
        String t = Formatting.strip(hs.getTitle().getString());
        if (t == null) return false;
        t = t.toLowerCase(Locale.ROOT);
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    private static void click(MinecraftClient client, ClientPlayerEntity player, Slot slot, SlotActionType type) {
        client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slot.id, 0, type, player);
    }

    private static void warn(ClientPlayerEntity player, String msg) {
        CommissionBot.status = msg;
        player.sendMessage(Text.literal("[Commissions] ").formatted(Formatting.AQUA)
                .append(Text.literal(msg).formatted(Formatting.YELLOW)), false);
    }
}
