# Multi Bind (Fabric 1.21.10)

Give any action more than one key. Jump on Space **and** Mouse 4, Attack on Left Click **and** R, or a mod's key on
two keys at once. The action's own key keeps working; each extra key presses, holds and clicks the action exactly the same way.

## Setting it up
Open **Mod Menu > Multi Bind > settings button** (needs [Mod Menu](https://modrinth.com/mod/modmenu) 16.x for 1.21.10),
or bind "Open Multi Bind settings" in Options > Controls.

* Every action is listed by category: its own key on the left, its extra keys on the right.
* **+ Add key**, then press a key or mouse button (Esc cancels). Up to 4 extra keys per action.
* Click an extra key to remove it. Keys also used by another action show in gold, with a tooltip saying which.
* Search by action name or key; "Showing: with extra keys" lists only the actions you've changed.
* **Remove all extra keys** clears everything.

The action's own key is still changed in Controls as usual. Extra keys are saved in `.minecraft/config/multibind.txt`.
