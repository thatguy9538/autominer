# Lane Walker: Fabric mod for Minecraft 1.21.10

Build a walking program out of Scratch-style blocks, then run it.

## Controls
| Input | What it does |
|-------|--------------|
| **B** | Open the block editor |
| **K** | Run / stop your program |
| `/walk edit`, `/walk run`, `/walk stop` | Same, from chat |

## The block editor
* **Blocks:** `walk [left] until wall`, `walk [right] [5] blocks` and `wait [1] seconds`.
* **Drag** blocks from the left palette into the orange **repeat** block, and drag them up or down to reorder them.
* **Drag a block back to the palette** to delete it.
* **Click a field** to change it: left-click = next / +1, right-click = back / -1, hold **Shift** for x10, or use the mouse wheel.
* The **repeat** field can be a number of times, or **forever** (right-click it down past 1).
* Buttons: **Run** (closes the editor and starts), **Stop**, **Reset** (back to left/right forever), **Done**.

Your program is saved automatically to `.minecraft/config/lanewalker.txt`.

Walk directions are relative to where you're looking. While it runs you can still look around and jump.
It pauses while a menu is open and stops if you die.

## Install
Put `lanewalker-2.0.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
