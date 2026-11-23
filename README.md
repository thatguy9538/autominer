# Sticky Keys (Fabric 1.21.10)

Make any key binding sticky: press it once and it **stays held** after you let go; press it again to let go.
Works for keyboard keys and mouse buttons (e.g. make **Attack** sticky to keep mining, **Forward** to auto-walk).

## Settings
Open **Mod Menu > Sticky Keys > settings button** (you need [Mod Menu](https://modrinth.com/mod/modmenu) 16.x for
1.21.10). You can also bind a key to "Open Sticky Keys settings" in Options > Controls.

* Every key binding is listed by category with its current key. Click **normal / STICKY** to switch it.
  Search by name or key at the top. A green dot shows keys that are held right now.
* **On / off key** (default **Y**): turns every sticky key on and off at once, with a message above the hotbar.
  Rebind it right in the Sticky Keys screen (click the key button, press a key; Esc cancels, Backspace unbinds) or in Controls.
* **Sticky Keys** ON/OFF button: the same switch.
* **Release in menus**: let go of held keys when you open the inventory, chat, etc. (default ON).
* **Held keys HUD**: shows little green chips for the keys being held; choose where.
* **Let go of all** (also bindable to a key) and **Make none sticky**.

Sneak and Sprint start out sticky. Settings are saved to `.minecraft/config/stickykeys.txt`.

## Works with Multi Bind
If the Multi Bind mod is installed, an action's extra keys from Multi Bind latch and unlatch it exactly like its own key.
