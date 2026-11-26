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

**Camera:** turns like a hand on a mouse: it eases in and out of turns (with a little momentum and overshoot), pitch
lags yaw so turns curve, it aims at a spot that drifts around the target's body, has a slight hand tremor, and waits
100-250 ms before reacting to a new target. `turn` sets its top speed.

**Failsafes:** it stops (with an alarm sound) if you're teleported 8+ blocks or your camera is turned by something else,
and stops if you die, run out of coins, or haven't unlocked the tier. It pauses while you have a menu open.

Limits: Inferno Demonlord's dagger attunement and Voidgloom's beacons aren't handled; Riftstalker isn't supported.

## License
GPL-3.0 (see LICENSE). The fighting logic (target scoring, reach, path-finding hand-off, re-path and blacklist rules,
attack timing jitter) is adapted from V5's CombatBot, https://github.com/V5-Client/V5 (GPL-3.0).
Walking uses the route-planner from Auto Miner.
