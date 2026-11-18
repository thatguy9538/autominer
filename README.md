# Powder Assist (Fabric 1.21.10)

Helpers for powder grinding in the Crystal Hollows.

## Keys
| Key | What |
|---|---|
| **U** | Menu |
| **J** | Hardstone auto-mine on/off |
| **N** | Chest solver on/off |

You can change them in Options > Controls > Powder Assist.

## Features
* **Chest solver**: when a treasure chest appears while you mine, the server puts crit particles on the spot you
  have to hit. The solver turns your camera to that spot and (with **Auto-click** on) clicks it, one spot after
  another, until the chest is lockpicked. Lockpick button: **Right-click** (default) or **Left-click**, in the menu.
* **Chest ESP**: treasure chests get an orange box that shows through walls, a distance label and a tracer line
  from your crosshair (green while being solved). New chests are spotted when they appear near you, or right after
  the "You uncovered a treasure chest!" message.
* **Auto-mine**: press J and it mines **hardstone** (stone) in reach. It picks the block that needs the least
  turning, turns smoothly (Turn speed 1-10 in the menu), holds left click until it breaks, and never digs out the
  block you're standing on. Gemstones (stained glass) and ores can be turned on too. It waits while the chest solver works.
* **Powder tracker HUD**: mithril / gemstone / glacite powder gained this session and per hour (from the tab list,
  or from chest reward messages if your tab doesn't show powder), chests opened / found and per hour, session
  time and active time (time you were actually getting powder). Corner and on/off in the menu; **Reset session** there too.

Settings are saved in `.minecraft/config/powderassist.txt`.

Using mods that mine or click for you is against most servers' rules and can get you banned.
