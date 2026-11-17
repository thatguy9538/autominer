# Auto Miner 2.0: Fabric mod for Minecraft 1.21.10

Pick one or more blocks, and the mod mines every matching block within your reach that you can see.
Built to cope with custom server blocks and drills.

## Controls
| Key | Action |
|-----|--------|
| **G** | Add the block you're looking at to the target list (press again to remove it) |
| **J** | Clear the target list |
| **H** | Turn mining on / off |
| **L** | Switch mode: **hold click** (default) / **classic** |

Rebind them in **Options → Controls → Key Binds → Auto Miner**.

## How it handles custom blocks and drills
* **Head blocks are told apart by their skin.** Adding a player-head block only targets heads with that same texture.
* **Ores with stages:** add each stage with G (e.g. gray wool, prismarine, light blue wool). Once it starts on a block it
  keeps hitting it until it's actually gone (air or bedrock), even if it changes into another block on the way.
* **Hold click mode (default):** turns you to face the block and holds left click, like you'd do yourself, so the server
  and your drill decide how fast it breaks. It only holds the button while your crosshair is on the target, so it never
  mines anything else. **Classic** mode uses Minecraft's normal breaking and works better for plain vanilla blocks.
* **Doesn't give up on flickers:** a block can be out of sight for half a second (particles, armor stands, moving) before
  it's dropped. A block that hasn't broken after 30 seconds (a ghost block, say) is skipped for 10 seconds.
* **Finds odd shapes:** it looks for a clear line of sight to 55 points on the block's real shape, so heads, slabs and
  blocks round corners are found.
* **Short pause** (0.25 s) after each block, so the server keeps up.

It pauses while a menu is open. Hold click mode needs the Minecraft window focused (the game lets go of the mouse when
you switch away).

> Many multiplayer servers ban auto-mining mods. Check the rules before using it there.

## Install
Put `autominer-2.0.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
Remove the old autominer jar.
