# Slayer Bot (Fabric 1.21.10)

Runs SkyBlock slayer quests by itself:
1. Starts a quest: runs `/slayer` and clicks through the menu (collect reward, your slayer, your tier, Confirm).
   If that command doesn't open a menu, start the quest yourself and it carries on.
2. Kills the slayer's mobs (zombies, spiders, wolves, endermen or blazes) near where you started, until the boss spawns.
3. Finds **your** boss (the one under "Spawned by: <you>") and kills it; it never attacks other players' bosses or their minions.
4. On "SLAYER QUEST COMPLETE!" it starts the next quest.

## Use
Stand in the slayer area and run `/slb start` (or bind "Start / stop Slayer Bot" in Controls). `/slb stop` stops.

`/slb` shows status and all settings: `type <revenant|tarantula|sven|voidgloom|inferno>`, `tier <1-5>`, `cps <5-15>`,
`turn <100-800>` (camera speed, degrees per second), `leash <10-80>` (blocks from the start spot), `here` (re-center the leash),
`path <5-30>`, `restart` (start the next quest by itself on/off), `button` (left click / right click for ability weapons),
`hud`, `command <cmd>` (the command that opens the slayer menu). Saved in `config/slayerbot.txt`.

**Ability item:** hold an item with a right-click ability (Hyperion, a healing wand...) and run `/slb ability set`.
The bot switches to it, right-clicks, and switches back to your weapon, with a short human-like pause each way.
`/slb ability cooldown <ms>` (default 5000), `when <always|boss|lowhp>` (in fights / only on your boss / when your
health is below `hp <10-90>`%), `range <2-20>` (how close a target must be), `clear`. It reads the server's
"on cooldown for Xs" and "not enough mana" messages and waits. It doesn't attack while the ability item is in hand.

**Camera:** modelled on how people actually aim, from motor-control research plus open-source aiming code:
* a new target is noticed after a human reaction time (ex-Gaussian, ~190 ms + a random tail);
* turns follow the minimum-jerk path (Flash & Hogan), with a duration from Fitts's law (bigger turns and smaller
  targets take longer); the first move lands near the target, usually a little short or past, then a short pause
  and a smaller correction;
* paths curve: pitch starts slightly after yaw, plus WindMouse's "wind" (Benjamin Land, GPL-3.0);
* moving targets get smooth pursuit (follows their motion with a slight lag) with catch-up flicks on top;
* slow drift, faint tremor and rare tiny hesitations;
* the aim point wanders inside the hitbox using LiquidBounce's Gaussian aim-point constants (GPL-3.0);
* the camera glides at your frame rate: each tick's turn is spread over every frame until the next tick, snapped to
  whole mouse-sensitivity steps and applied through Minecraft's own mouse-look, like real mouse input.
`turn` sets its top speed (default 400 degrees per second).

**Failsafes:** it stops (with an alarm sound) if you're teleported 8+ blocks or your camera is turned by something else,
and stops if you die, run out of coins, or haven't unlocked the tier. It pauses while you have a menu open.

Limits: Inferno Demonlord's dagger attunement and Voidgloom's beacons aren't handled; Riftstalker isn't supported.

## License
GPL-3.0 (see LICENSE). The fighting logic (target scoring, reach, path-finding hand-off, re-path and blacklist rules,
attack timing jitter) is adapted from V5's CombatBot, https://github.com/V5-Client/V5 (GPL-3.0).
Walking uses the route-planner from Auto Miner.
Camera: WindMouse by Benjamin Land (GPL-3.0, https://ben.land/post/2021/04/25/windmouse-human-mouse-movement/);
aim-point Gaussian constants, the lazy aim point, short stops and sensitivity snapping adapted from LiquidBounce
(GPL-3.0, https://github.com/CCBlueX/LiquidBounce).
