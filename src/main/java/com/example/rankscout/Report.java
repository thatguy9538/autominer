package com.example.rankscout;

import java.time.Instant;

/** Builds the self-contained HTML report (data embedded, no internet needed). */
final class Report {

    private Report() {
    }

    static String build(Store.Data d, String json, Instant now) {
        String safe = json.replace("</", "<\\/");
        return TEMPLATE.replace("__DATA__", safe).replace("__NOW__", String.valueOf(now.toEpochMilli()));
    }

    private static final String TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rank Scout: who's ahead of you</title>
<style>
:root { --bg:#f3f4f8; --panel:#fff; --ink:#23263a; --muted:#6a7090; --line:#dfe2ec; --accent:#2f7d4f; --me:#fff3c4; --bar:#cfe9d8; color-scheme:light; }
@media (prefers-color-scheme: dark) { :root { --bg:#15171f; --panel:#1d2029; --ink:#e6e8f0; --muted:#8c93ad; --line:#2d3142; --accent:#6fd39a; --me:#4a3f12; --bar:#1f4a33; color-scheme:dark; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font:14px/1.45 system-ui,"Segoe UI",sans-serif; }
header { padding:18px 22px 10px; background:var(--panel); border-bottom:1px solid var(--line); }
h1 { margin:0 0 4px; font-size:20px; }
.sub { color:var(--muted); font-size:13px; }
.tiles { display:flex; gap:10px; flex-wrap:wrap; margin-top:12px; }
.tile { border:1px solid var(--line); border-radius:8px; padding:8px 12px; min-width:120px; }
.tile b { display:block; font-size:20px; font-variant-numeric:tabular-nums; }
.tile span { color:var(--muted); font-size:12px; }
nav { display:flex; gap:6px; padding:10px 22px; flex-wrap:wrap; align-items:center; }
nav button { font:inherit; padding:6px 14px; border-radius:999px; border:1px solid var(--line); background:var(--panel); color:var(--ink); cursor:pointer; }
nav button.on { background:var(--accent); color:#fff; border-color:transparent; }
nav input { margin-left:auto; font:inherit; padding:6px 10px; border-radius:6px; border:1px solid var(--line); background:var(--panel); color:var(--ink); min-width:220px; }
main { padding:6px 22px 30px; display:grid; grid-template-columns:repeat(auto-fill,minmax(340px,1fr)); gap:14px; }
.card { background:var(--panel); border:1px solid var(--line); border-radius:10px; overflow:hidden; }
.card h2 { margin:0; font-size:15px; padding:10px 12px; border-bottom:1px solid var(--line); display:flex; justify-content:space-between; gap:8px; }
.card h2 small { color:var(--muted); font-weight:400; }
table { width:100%; border-collapse:collapse; font-variant-numeric:tabular-nums; }
td, th { padding:5px 12px; text-align:left; border-bottom:1px solid var(--line); }
th { color:var(--muted); font-weight:500; font-size:12px; }
td.n { text-align:right; white-space:nowrap; }
tr.me td { background:var(--me); font-weight:600; }
.barcell { position:relative; }
.barcell i { position:absolute; inset:3px auto 3px 0; background:var(--bar); border-radius:3px; z-index:0; }
.barcell span { position:relative; z-index:1; }
.empty { color:var(--muted); padding:30px; grid-column:1/-1; text-align:center; }
.player a { color:var(--accent); cursor:pointer; text-decoration:underline; }
</style>
</head>
<body>
<header>
  <h1>Rank Scout: who's ahead of you on Fakepixel</h1>
  <div class="sub" id="sub"></div>
  <div class="tiles" id="tiles"></div>
</header>
<nav>
  <button data-tab="skills" class="on">Skills</button>
  <button data-tab="collections">Collections</button>
  <button data-tab="players">Players</button>
  <input id="q" type="search" placeholder="Filter by player, skill or collection">
</nav>
<main id="main"></main>
<script>
const D = __DATA__; const NOW = __NOW__;
const me = D.me || {name:"you", skills:{}, collections:{}};
const players = Object.values(D.players || {});
let tab = "skills";
const fmt = n => n < 0 ? "?" : n >= 1e9 ? (n/1e9).toFixed(2)+"B" : n >= 1e6 ? (n/1e6).toFixed(2)+"M" : n >= 1e4 ? (n/1e3).toFixed(1)+"k" : Math.round(n*10)/10+"";
const val = s => s ? (s.amount >= 0 ? s.amount : s.level) : -1;
const show = s => s ? (s.level >= 0 ? "Lv " + fmt(s.level) : "") + (s.amount >= 0 ? (s.level >= 0 ? " · " : "") + fmt(s.amount) : "") : "?";
const ago = t => { const m = Math.round((NOW - t)/60000); return m < 60 ? m+" min ago" : m < 1440 ? Math.round(m/60)+" h ago" : Math.round(m/1440)+" days ago"; };
const esc = s => String(s).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));

function statNames(kind) {
  const set = new Set(Object.keys(me[kind] || {}));
  for (const p of players) for (const k of Object.keys(p[kind] || {})) set.add(k);
  return [...set].sort();
}
function rows(kind, stat) {
  const r = players.filter(p => p[kind] && p[kind][stat]).map(p => ({name:p.name, s:p[kind][stat], t:p.scanned}));
  r.sort((a,b) => val(b.s) - val(a.s));
  return r;
}
function card(kind, stat, q) {
  const r = rows(kind, stat).filter(x => !q || x.name.toLowerCase().includes(q) || stat.toLowerCase().includes(q));
  if (q && !r.length && !stat.toLowerCase().includes(q)) return "";
  const mine = (me[kind] || {})[stat];
  const top = Math.max(val(mine), ...r.map(x => val(x.s)), 1);
  let h = '<div class="card"><h2>' + esc(stat) + '<small>' + r.length + ' ahead of you</small></h2><table><tr><th>#</th><th>Player</th><th class="n">Level / amount</th></tr>';
  r.forEach((x,i) => { h += '<tr><td>' + (i+1) + '</td><td class="player"><a onclick="openPlayer(\\'' + esc(x.name) + '\\')">' + esc(x.name) + '</a></td><td class="n barcell"><i style="width:' + Math.max(2, 100*val(x.s)/top) + '%"></i><span>' + show(x.s) + '</span></td></tr>'; });
  h += '<tr class="me"><td>' + (r.length+1) + '</td><td>' + esc(me.name) + ' (you)</td><td class="n">' + show(mine) + '</td></tr></table></div>';
  return h;
}
function render() {
  const q = document.getElementById("q").value.trim().toLowerCase();
  let h = "";
  if (tab === "players") {
    const list = players.filter(p => !q || p.name.toLowerCase().includes(q)).sort((a,b) => (Object.keys(b.skills).length+Object.keys(b.collections).length) - (Object.keys(a.skills).length+Object.keys(a.collections).length));
    for (const p of list) {
      h += '<div class="card" id="p-' + esc(p.name) + '"><h2>' + esc(p.name) + '<small>scanned ' + ago(p.scanned) + '</small></h2><table><tr><th>Ahead of you in</th><th class="n">Them</th><th class="n">You</th></tr>';
      for (const [k,s] of Object.entries(p.skills)) h += '<tr><td>' + esc(k) + ' (skill)</td><td class="n">' + show(s) + '</td><td class="n">' + show(me.skills[k]) + '</td></tr>';
      for (const [k,s] of Object.entries(p.collections)) h += '<tr><td>' + esc(k) + '</td><td class="n">' + show(s) + '</td><td class="n">' + show(me.collections[k]) + '</td></tr>';
      h += '</table></div>';
    }
  } else {
    for (const s of statNames(tab)) h += card(tab, s, q);
  }
  document.getElementById("main").innerHTML = h || '<div class="empty">Nothing yet. Join Fakepixel and stand still for a bit: Rank Scout scans players around you and fills this in.</div>';
}
function openPlayer(n) { tab = "players"; setTabs(); document.getElementById("q").value = n; render(); }
function setTabs() { document.querySelectorAll("nav button").forEach(b => b.classList.toggle("on", b.dataset.tab === tab)); }
document.querySelectorAll("nav button").forEach(b => b.onclick = () => { tab = b.dataset.tab; setTabs(); render(); });
document.getElementById("q").oninput = render;
document.getElementById("sub").textContent = "Compared with " + me.name + (D.me ? " (your stats from " + ago(D.me.scanned) + ")" : " (your own profile hasn't been scanned yet)") + ". Only players ahead of you are kept, per stat. Updated " + new Date(NOW).toLocaleString() + ".";
const ns = statNames("skills").length, nc = statNames("collections").length;
document.getElementById("tiles").innerHTML = [[players.length,"players ahead of you"],[ns,"skills tracked"],[nc,"collections tracked"],[Object.keys(D.lastScan||{}).length,"profiles scanned"]].map(([n,l]) => '<div class="tile"><b>' + n + '</b><span>' + l + '</span></div>').join("");
render();
</script>
</body>
</html>
""";
}
