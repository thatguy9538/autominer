# Crosshair Aim: Fabric mod for Minecraft 1.21.10

Type a direction in chat and your crosshair snaps exactly to it.

## Commands
| Command | What it does |
|---------|--------------|
| `/aim <x> <y>` | Turn to face yaw **x** and pitch **y** exactly |
| `/aim` | Show the direction you're facing right now |

* **x = yaw** (left/right). These are the same numbers as "Facing" on the F3 screen.
  South = 0, West = 90, North = 180 / -180, East = -90. Numbers outside -180 to 180 wrap around.
* **y = pitch** (up/down). -90 is straight up, 0 is level, 90 is straight down.
* Decimals work, for example `/aim 45.5 -12.25`.

## Install
1. Install the Fabric Loader for 1.21.10: https://fabricmc.net/use/installer/
2. Put Fabric API for 1.21.10 in `.minecraft/mods`: https://modrinth.com/mod/fabric-api
3. Put `crosshairaim-1.0.0.jar` in the same `mods` folder.

## Build it yourself
With Java 21, run `./gradlew build` (macOS/Linux) or `gradlew.bat build` (Windows).
The jar is created in `build/libs/`.
