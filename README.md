# Visitor Waypoints (Fabric 1.21.10)

Open your **Visitor's Logbook** in the Garden and flip through its pages. The mod reads which visitors you
haven't unlocked yet and puts waypoints where you meet them (e.g. "Talk to the Alchemist" -> the Alchemist in the
Hub's Farm). It only shows:
* visitors your **Garden level** allows (the logbook's own tick/cross if it shows one, otherwise your level vs the wiki), and
* waypoints on the **island you're on** (from the tab list "Area:", or the sidebar zone).

**Requirements from your logbook:** while you flip through the Visitor's Logbook the mod also copies each
visitor's unlock requirements as the logbook lists them (lines under "Requirement(s)" and any line with a tick
or cross). Name tags and `/vw list` show those, ticked green or crossed red, with "(from your logbook)". The
wiki's text is only used for visitors the logbook hasn't shown requirements for. A visitor counts as unlocked
when it has visited, or when the logbook ticks every requirement.

**Island search:** every second the mod reads the nametags of all NPCs your game has loaded and remembers where
each visitor's NPC really stands, per island. Those spots replace the wiki's (labelled "spotted here" instead of
"wiki: zone"), and visitors the wiki has no spot for get a waypoint once you've walked near them. The server only
sends NPCs near you, so the island fills in as you explore. Spots seen in the Garden are ignored (visitors show up
there too). `/vw forgetspots` clears them.

Waypoints are boxes with a name tag: the visitor's name, rarity and distance, then **How to get as a visitor** (the Garden level, ticked or crossed against yours, and each requirement on its own line), visible through walls, with a tracer
line to the nearest one. When chat shows `[NPC] Name:` for a "Talk to" visitor, that waypoint disappears.

Commands: `/vw` (status), `/vw list`, `/vw toggle`, `/vw tracer`, `/vw level <n>`, `/vw forgetspots`, `/vw reset`.
A key to show/hide waypoints can be set in Controls.

Locations come from the Hypixel SkyBlock Wiki (Garden Visitors/List and each NPC's page), read 2026-11-19:
148 visitors, 109 with a place to go. Not included (no fixed spot on the wiki): Duncan, Trinity, Tomioka (Catacombs
rooms), Odawa, Xalx, Pete, Romero, Garden-only and Mayor visitors.

Files: `config/visitorwaypoints-logbook.json` (what was read) and `config/visitorwaypoints-logbook-dump.txt`
(the logbook items as the mod saw them, useful if something isn't read right).
