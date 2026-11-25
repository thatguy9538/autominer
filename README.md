# Rank Scout (Fabric 1.21.10)

Every time you join Fakepixel it starts by itself: it reads your own profile, then opens the profile of each player
around you (`/viewprofile <name>`), opens each of their profiles from Fakepixel's Select Profile menu, reads their skills and collections (keeping the best value across their profiles, and following the profile's Skills and
Collections buttons), and closes it. For each skill and collection, a player's value is only kept if it beats yours;
players who aren't ahead of you in anything aren't stored.

The report is `.minecraft/rankscout/ranks.html` (open it with `/ranks open`): a table per skill and per collection of
everyone ahead of you, with you at the bottom, a Players tab, and a search box. Raw data: `rankscout/data.json`.

* Scans only while you stand still (the profile menu shows on your screen), one profile every 8 seconds,
  and each player at most every 12 hours. Change these in `rankscout/settings.txt`.
* `/ranks` status, `/ranks open`, `/ranks toggle` (also a key in Controls), `/ranks scan <player>`, `/ranks me`
  (re-read your profile), `/ranks dump` (save the next profile's menus to `rankscout/menu-dump.txt`).
