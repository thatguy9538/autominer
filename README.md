# Wardrobe Keys (Fabric 1.21.10)

One key per SkyBlock wardrobe slot (1-18). Press it and the mod opens `/wardrobe`, turns to page 2 if needed,
clicks that slot's equip button and closes the menu.

* Set the keys in **Options > Controls > Wardrobe Keys** (they start unbound so they don't clash with your hotbar).
* Or type `/wd <slot>`, e.g. `/wd 3`.
* `/wd close` turns closing the wardrobe afterwards on/off.
* It says so in chat if the slot is locked, empty or already on.

The button is found by its name ("Slot 3: ..."), so it works even if the layout differs. If it can't find it, it writes
the wardrobe's items to `config/wardrobekeys-dump.txt`. `config/wardrobekeys.txt` also lets you change the command
that opens the wardrobe.
