# Ghost Bot (Fabric 1.21.10)

Kills the **Ghosts** in **The Mist** (the bottom of the Dwarven Mines) by itself. Ghosts are invisible charged
creepers; they drop Sorrow, Volta, Plasma, Ghostly Boots, Bag of Cash and coins, and give lots of Combat XP.

* Fights **anywhere in The Mist**: it reads the sidebar to know when it's in The Mist, maps the area as it walks,
  and turns back if it ever wanders out. (`/gb area` switches to staying within a leash of the start spot instead.)
* Finds ghosts (even though they're invisible) and goes for the best one: close, little turning,
  and standing with other ghosts (so area abilities hit more of them). Skips ghosts another player is fighting.
* Walks straight to close ones and path-finds to far ones, re-plans when a ghost wanders off, and gives up on ones it
  can't reach for 5 s.
* When nothing is in sight, it heads to where it last saw ghosts, otherwise explores the parts of The Mist it hasn't
  checked for the longest time, so it covers the whole area instead of standing still.
* Chains kills quickly: a ghost near the one you just killed is picked up almost immediately.
* Counts kills (and kills per hour), coins dropped and rare drops on the HUD and in `/gb stats`.

## Use
Go down to The Mist and run `/gb start` (or bind
"Start / stop Ghost Bot" in Controls). `/gb stop` stops and prints your stats.

`/gb` shows status and all settings: `area` (all of The Mist, the default / leash only), `cps <5-15>`, `turn <100-800>` (camera speed, degrees per second),
`leash <10-80>` (blocks from the start spot, only used with `area` off), `here` (re-center the leash), `path <5-30>`,
`button` (left click / right click for ability weapons), `hud`, `stats`. Saved in `config/ghostbot.txt`.

**Ability item:** hold an item with a right-click ability (a Hyperion or other area weapon, a healing wand...) and run
`/gb ability set`. The bot switches to it, right-clicks, and switches back to your weapon, with a short human-like pause
each way. `/gb ability cooldown <ms>` (default 5000), `when <always|lowhp|group>` (in fights / when your health is
below `hp <10-90>`% / when `group <2-10>` ghosts are within `range <2-20>` blocks: the default, best for area weapons),
`clear`. It reads the server's "on cooldown for Xs" and "not enough mana" messages and waits.

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
and stops if you die. It pauses while you have a menu open, and warns you if the sidebar doesn't say you're in The Mist.
If it walks out of The Mist it stops fighting and goes straight back to the last spot that was inside.

Tips: ghosts hit hard and drain your health fast at low gear, so wear good armor (or use a healing ability with
`when lowhp`). Bots break the rules on most servers, Fakepixel included; you can be banned. Use it at your own risk.

## License
GPL-3.0 (see LICENSE). The fighting logic (target scoring, reach, path-finding hand-off, re-path and blacklist rules,
attack timing jitter) is adapted from V5's CombatBot, https://github.com/V5-Client/V5 (GPL-3.0).
Walking uses the route-planner from Auto Miner.
Camera: WindMouse by Benjamin Land (GPL-3.0, https://ben.land/post/2021/04/25/windmouse-human-mouse-movement/);
aim-point Gaussian constants, the lazy aim point, short stops and sensitivity snapping adapted from LiquidBounce
(GPL-3.0, https://github.com/CCBlueX/LiquidBounce).
