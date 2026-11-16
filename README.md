# Lane Walker: Fabric mod for Minecraft 1.21.10

Build a walking program out of Scratch-style blocks, then run it.

## Controls
| Input | What it does |
|-------|--------------|
| **B** | Open the block editor |
| **K** | Run / stop your program |
| `/walk edit`, `/walk run`, `/walk stop` | Same, from chat |

## The block editor
* **Blocks:** `walk [left] until wall`, `walk [right] [5] blocks`, `set angle x [0] y [0]`, `go to x [] y [] z []`, `stop at x [0] z [0]`, `if done → run next ●`, `wait [1] seconds` and `random wait [0.5] to [2.0] s`.
* **set angle x [0] y [0]** turns you to face an exact direction, like the F3 "Facing" numbers.
  x = left/right (0 south, 90 west, 180 north, -90 east); y = up/down (-90 up, 0 level, 90 down).
  Because walk directions are relative to where you look, use it to point yourself before walking.
  Click = +1°, right-click = -1°, Shift = 10°, or scroll.
* **go to x [] y [] z []** takes you to that block, then moves on to the next block.
  If the game lets you fly (Creative, or a server that allows `/fly`), it turns flying on and flies there.
  Otherwise it walks there on the ground (ignoring y) and hops up 1-block steps.
  It stops the program if it can't get any closer for 10 seconds.
  When you drag it out of the palette, it fills in the spot you're standing on.
* **stop at x [0] z [0]** stops the whole program as soon as you step onto that block.
  x and z are the first and third numbers of "XYZ" on the F3 screen (y is height, so it's ignored).
  It works wherever you put it in the script, and you can use more than one.
  When you drag it out of the palette, it fills in the spot you're standing on.
  If you start the program while standing on the spot, it only stops once you come back to it.
* **if done → run next ●** works like an `if` in code. Click its **●**, then click another block to tie
  them together with a coloured string (the string is the colour of the block it's tied to).
  When the program reaches the if, it checks whether the tied block **completed** the last time it ran:
  * yes: the block right under the if runs
  * no (or it hasn't run yet): the block right under the if is skipped
  A block "completes" when it finishes normally. `walk N blocks` doesn't complete if a wall stops it early;
  `walk until wall`, waits, set angle and a successful go to always complete.
  Right-click the ● to cut the string.
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
Put `lanewalker-2.6.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
