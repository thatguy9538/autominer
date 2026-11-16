# Lane Walker: Fabric mod for Minecraft 1.21.10

Build a walking program out of Scratch-style blocks, then run it.

## Controls
| Input | What it does |
|-------|--------------|
| **B** | Open the block editor |
| A script's key (K by default) | Run that script, or stop it if it's running |
| `/walk edit`, `/walk run`, `/walk stop` | Open the editor, run the first script, stop |

## The block editor
* **Scripts:** every script starts with a yellow **when [K] key pressed** block, and blocks snap underneath it.
  Drag a new hat out of the **Events** section to add another script (up to 8); new scripts start empty.
  Click the key box, then press any key to choose that script's key (Esc cancels, Backspace clears;
  right-click the box to clear it). Two scripts can't share a key.
  Click the green **▶** on a script to run it right away. Drag a script's hat block back to the palette to delete the whole script.
  Only one script moves you at a time: pressing another script's key switches to it.
* **Dragging:** a dark line shows where a block will land: under another block, or inside a repeat / if.
  Dragging a repeat or if block brings everything inside it along. Drop a block on the palette to delete it.
* **Motion blocks (blue):** `walk [dir] until wall`, `walk [dir] [5] blocks`, `set angle x [0] y [0]`, `go to x [] y [] z []`.
* **Control blocks (orange):**
  * **repeat [forever ▾]** / **repeat [5] [times ▾]**: a C block that runs the blocks inside it over and over.
  * **if at x [] z []**: a C block. The blocks inside only run if you're standing on that block right then;
    otherwise the whole section is skipped. Any **if done** tied to it by a string also counts it as not done,
    so its section is skipped too.
  * **if done ●**: a C block. Click its ● and then another block to tie them with a string (in that block's colour).
    The blocks inside only run if the tied block completed the last time it ran. Right-click ● to cut the string.
  * `wait [1] seconds`, `random wait [0.5] to [2.0] s`, `stop at x [] z []` (stops the script the moment you step on that spot, wherever it sits in the script).
* **Keys block (light blue):** **hold [left click] for [1.0] s** holds a key or mouse button down for that long,
  then lets go and moves on. Click the key box, then press any key or click a mouse button (left / right / middle)
  to choose it; Esc cancels. The time counts in 0.1 s steps (Shift = 1 s). Holding a movement key (W, A, S, D)
  works too, and so does holding left click to keep mining or right click to keep using an item.
* A block "completes" when it finishes normally. `walk N blocks` doesn't complete if a wall stops it early;
  an **if at** completes only when you were on its spot.
* **Drop-downs (▾)** open a list when you click them. **Number boxes:** click = +1, right-click = -1, Shift = x10, or scroll.
* When you drag out **go to**, **stop at** or **if at**, it fills in the spot you're standing on.
* A script with no repeat runs once from top to bottom, then stops ("finished").
* Buttons: **Stop**, **Reset** (back to one left/right script), **Done**.

Your scripts are saved automatically to `.minecraft/config/lanewalker.txt`.

Walk directions are relative to where you're looking. While it runs you can still look around and jump.
It pauses while a menu is open and stops if you die.

## Install
Put `lanewalker-4.1.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
