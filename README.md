# Lane Walker: Fabric mod for Minecraft 1.21.10

Walks you one way until you hit a wall, then switches and walks the other way,
back and forth until you stop it.

## Controls
| Input | What it does |
|-------|--------------|
| **K** | Start / stop. Uses your last directions, or left then right the first time |
| `/walk <first> <second>` | Walk `<first>` until a wall, then `<second>`, and repeat |
| `/walk <first> <second> <blocks>` | Same, but also switch after going that many blocks |
| `/walk stop` | Stop |
| `/walk repeat <times>` | Do that many round trips, then stop by itself |
| `/walk repeat forever` | Keep going until you stop it (the default) |

Directions are `forward`, `back`, `left` and `right`, relative to where you're looking.
Example: `/walk repeat 5`, then `/walk left right`, goes left-right 5 times and stops.

* You can still look around and jump while it walks.
* It pauses while a menu is open and stops if you die.
* Change the K key in Options → Controls → Key Binds → Lane Walker.

## Install
Put `lanewalker-1.1.0.jar` and Fabric API (1.21.10) in `.minecraft/mods`, then launch with Fabric Loader for 1.21.10.
