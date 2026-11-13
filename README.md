# Auto Miner — Fabric mod for Minecraft 1.21.10

Pick a block type, and the mod automatically mines every block of that type
that is within your reach and that you can see.

## Controls
| Key | Action |
|-----|--------|
| **G** | Select the block you're looking at as the target |
| **H** | Turn auto-mining on / off |

Rebind them in **Options → Controls → Key Binds → Auto Miner**.

* It mines the closest matching block first, then moves on to the next one.
* It only mines blocks you have a clear line of sight to, so it won't dig ores out from behind stone.
* It pauses while a menu or your inventory is open.
* It uses whatever you're holding, so hold the right tool.

## Build it
You need **Java 21** (for example [Adoptium Temurin 21](https://adoptium.net)).

In this folder, run:

```
# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

The first build downloads Minecraft and Fabric, so it can take a few minutes.
The mod jar ends up at **`build/libs/autominer-1.0.0.jar`**. Ignore the `-sources` jar.

## Install it
1. Install the **Fabric Loader** for 1.21.10: https://fabricmc.net/use/installer/
2. Put **Fabric API** for 1.21.10 in your `.minecraft/mods` folder: https://modrinth.com/mod/fabric-api
3. Put `autominer-1.0.0.jar` in the same `mods` folder.
4. Launch the game with the Fabric 1.21.10 profile.

To test without installing, run `gradlew runClient`.

> This is a client-side automation mod. It's fine in single-player, but many
> multiplayer servers ban auto-mining mods, so check the rules before using it there.
