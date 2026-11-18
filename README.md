# Auto Miner 3.1: Fabric mod for Minecraft 1.21.10

Pick one or more blocks, and the mod mines every matching block within your reach that you can see.
Built to cope with custom server blocks and drills.

## Controls
| Key | Action |
|-----|--------|
| **O** | Open the Auto Miner menu |
| **K** | Start / stop doing commissions |
| **G** | Add the block you're looking at to the target list (press again to remove it) |
| **J** | Clear the target list |
| **H** | Turn mining on / off |
| **L** | Switch mode: **hold click** (default) / **classic** |

Rebind them in **Options → Controls → Key Binds → Auto Miner**.

## The menu (O)
* **Mining ON/OFF**, **Mode** (hold click / classic) and **+ Add looked-at** (adds or removes the block under your crosshair).
* **Settings:** **Turn** (how fast the camera turns, 5-60% of the remaining angle per tick), **Pause** after each block
  (0-2 s), **Path** (how many blocks to plan ahead, 1-40). Use - and +.
* **Targets**, highest priority first. Each has a **priority from 1 (Lowest) to 5 (Highest)**, default 3; use - and +
  to change it, and the red X to remove it. Scroll if the list is long.
* Everything (targets, priorities, settings) is saved to `.minecraft/config/autominer.txt`.

## Commissions (Dwarven Mines)
Press **K** (or O > **Commissions...** > Commissions: OFF) and it does commissions by itself:
1. **Reads your commissions** from the tab list (lines like `Royal Mines Mithril: 45%` / `Goblin Slayer: DONE`; `100%` also counts as done).
2. **Etherwarps there** (if an **Aspect of the Void** is in your hotbar): it looks for a block you can see within
   ~55 blocks, with 2 air blocks on top, that gets you closest to where you're going, aims at it, sneaks and right-clicks,
   and repeats. The last ~8 blocks (and any jump it can't make: no AOTV, nothing to land on, 3 failed jumps e.g. out of
   mana) are walked. Switch to walking only with **Travel: Etherwarp / Walk** in the Commissions menu.
   **Walking**: it plans a walking route block by block (around walls, up 1-block steps, down drops of up to
   3 blocks, never onto lava / fire / magma, keeping away from ledges and lava), then walks it in smooth straight
   lines (it steers at the furthest point it can reach in a straight line instead of going block to block). It
   doesn't sprint into jumps or drops, or for the last few blocks. It plans the next part of a long route before the current one runs out, re-plans if
   something blocks the way, and when it's stuck it wiggles sideways and jumps before re-planning.
3. **Does it:**
   * **Mithril / Titanium** commissions (any area, or area ones like *Royal Mines Mithril*): mines with Auto Miner
     (mithril = gray wool, cyan terracotta, all prismarine, light blue wool (first); titanium = polished diorite; titanium only appears when you mine mithril, so titanium commissions mine
     mithril too and grab any titanium first).
     When nothing's left in reach it walks to the nearest exposed ore nearby (staying within 40 blocks of the area), stopping
     only where it can actually see the ore. Ores it couldn't mine or reach are skipped for 60 s.
   * **Goblin Slayer** (Goblin Burrows), **Glacite / Ice Walker Slayer** (Great Ice Wall), **Treasure Hoarder** and
     **Star Sentry Puncher**: finds the mob by its nametag, walks up and hits it.
   * Powder collector, raffle, raid and other event commissions are skipped.
4. **Claims** finished ones as soon as chat says *Commission Complete!* (or the tab says DONE): uses a **Royal Pigeon**
   from your hotbar if you have one, otherwise walks to the **nearest Emissary** (King, Fraiser, Carlton, Ceanna,
   Wilson, Lilith) or your own **Claim NPC** spot if you set one. It clicks every completed commission, then switches
   back to your drill.
5. **Upkeep** (each one can be turned off in the Commissions menu):
   * **Refuel**: when chat says your drill is empty, or its lore shows under 150 fuel, it walks to **Jotraeline**,
     puts the drill and your best fuel (Volta > Oil Barrel > Biofuel > Goblin Egg > coal) in the anvil, refuels,
     and puts the drill back in the same hotbar slot.
   * **Sell**: when your inventory is full it runs **/bz**, clicks *Sell Inventory Now* and confirms. If things are
     left that can't be sold, it stops trying for 5 minutes and tells you.
   * **Boost**: while mining it right-clicks the drill whenever *Mining Speed Boost is now available!*.
   If something goes wrong (no fuel, menu didn't open...) it tells you in chat and waits 5 minutes before trying again.
6. Repeats.

**Locations:** every area, Jotraeline and the Emissaries have **built-in Hypixel coordinates** (yellow "(built-in)" in
O > **Commissions...**). If Fakepixel's map is different, stand in the area and click **Set here**: your spot (green)
always wins. Turn **Built-in: OFF** to use only your own spots. Locations are saved to
`.minecraft/config/autominer-commissions.txt`. The status shows above the hotbar and in the Commissions menu.

It pauses while any other menu is open, waits after you die, and gives up walking somewhere after several failed tries.
Your own Auto Miner targets are put back when it stops.

## Priorities
* Higher priority blocks are always picked first.
* Among blocks with the same priority it prefers ones **touching** the last block in the path (so it follows veins),
  then the closest.
* Once a second, if a block with a **higher** priority than anything in the planned path is in reach and in sight, it
  throws the plan away and goes there next (it still finishes the block it's already breaking).

## How it handles custom blocks and drills
* **Head blocks are told apart by their skin.** Adding a player-head block only targets heads with that same texture.
* **Ores with stages:** add each stage with G (e.g. gray wool, prismarine, light blue wool). Once it starts on a block it
  keeps hitting it until it's actually gone (air or bedrock), even if it changes into another block on the way.
* **Hold click mode (default):** turns you to face the block and holds left click, like you'd do yourself, so the server
  and your drill decide how fast it breaks. It only holds the button while your crosshair is on the target, so it never
  mines anything else. **Classic** mode uses Minecraft's normal breaking and works better for plain vanilla blocks.
* **Doesn't give up on flickers:** a block can be out of sight for half a second (particles, armor stands, moving) before
  it's dropped. A block that hasn't broken after 10 seconds (decoration the server won't let you break, a ghost block) is skipped for 5 minutes.
* **Finds odd shapes:** it looks for a clear line of sight to 55 points on the block's real shape, so heads, slabs and
  blocks round corners are found.
* **Stays in reach:** a block only counts if its centre is within your reach (minus a small safety margin), and every
  queued block is re-checked each tick, so the path never includes a block you can't reach. Blocks that drop out of
  reach (because you moved) are taken out of the path straight away.
* **Paths:** it plans a chain of up to 12 connected target blocks (touching each other, diagonals count) and mines
  them in order, so it works along a vein instead of jumping around. If a queued block changes before it gets there
  (someone else mined it, it changed stage, it regenerated), that block is skipped. When only 2 blocks are left in the
  path it plans the next one, starting from the target closest to where the current path ends.
* **Smooth camera:** it turns quickly when the next block is far off to the side, then slows down as it lines up,
  instead of snapping. Hold click mode only presses the button once the crosshair is on the block.
* **Short pause** (0.25 s) after each block, so the server keeps up.
* **Aims at mining particles:** when the server puts a particle on the face of the block you're mining (like the
  "Precision Mining" target on Skyblock-style servers, which makes mining faster while you look at it), it turns to look
  straight at it, in both modes. Block-break debris is ignored, and it goes back to its normal aim 2 s after the last particle.

It pauses while a menu is open. Hold click mode needs the Minecraft window focused (the game lets go of the mouse when
you switch away).

> Many multiplayer servers ban auto-mining mods. Check the rules before using it there.

## Install
Put `autominer-3.1.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
Remove the old autominer jar.
