# Lane Walker: Fabric mod for Minecraft 1.21.10

Build a walking program out of Scratch-style blocks, then run it.

## Controls
| Input | What it does |
|-------|--------------|
| **B** | Open the block editor |
| **K** | Run / stop your program |
| `/walk edit`, `/walk run`, `/walk stop` | Same, from chat |

## The block editor
* **Blocks:** `walk [left] until wall`, `walk [right] [5] blocks`, `set angle x [0] y [0]`, `wait [1] seconds` and `random wait [0.5] to [2.0] s`.
* **set angle x [0] y [0]** turns you to face an exact direction, like the F3 "Facing" numbers.
  x = left/right (0 south, 90 west, 180 north, -90 east); y = up/down (-90 up, 0 level, 90 down).
  Because walk directions are relative to where you look, use it to point yourself before walking.
  Click = +1°, right-click = -1°, Shift = 10°, or scroll.
* **random wait** pauses for a different random time (to the nearest 0.1 s) between the two numbers every time it runs. On it, a click changes the number by 0.1 s and Shift + click by 1 s.
* **Drag** blocks from the left palette into the orange **repeat** block, and drag them up or down to reorder them.
* **Drag a block back to the palette** to delete it.
* **Drop-downs (▾)** open a list when you click them:
  * on walk blocks: the direction (forward / back / left / right), and **until wall** or **blocks**
  * on the repeat block: **forever** or **times**
* **Number boxes:** left-click = +1, right-click = -1, hold **Shift** for x10, or use the mouse wheel.
* Buttons: **Run** (closes the editor and starts), **Stop**, **Reset** (back to left/right forever), **Done**.

Your program is saved automatically to `.minecraft/config/lanewalker.txt`.

Walk directions are relative to where you're looking. While it runs you can still look around and jump.
It pauses while a menu is open and stops if you die.

## Install
Put `lanewalker-2.3.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
