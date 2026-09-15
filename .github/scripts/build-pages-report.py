#!/usr/bin/env python3
"""Build a static GitHub Pages playback dashboard from CI metric JSON artifacts."""

from __future__ import annotations

import json
import math
import os
import re
import statistics
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(os.environ.get("PAGES_SRC", "pages-src"))
OUT = Path(os.environ.get("PAGES_OUT", "pages-out"))
RUN_ID = os.environ.get("GITHUB_RUN_ID", "local")
RUN_NUMBER = os.environ.get("GITHUB_RUN_NUMBER", "0")
SHA = (os.environ.get("GITHUB_SHA") or "")[:7]
REF = os.environ.get("GITHUB_REF_NAME", "")
SERVER_URL = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
REPO = os.environ.get("GITHUB_REPOSITORY", "")

WARMUP_SEC = 5.0
COOLDOWN_SEC = 5.0


def mean(xs: list[float]) -> float | None:
    return statistics.fmean(xs) if xs else None


def pct(xs: list[float], p: float) -> float | None:
    if not xs:
        return None
    s = sorted(xs)
    idx = min(len(s) - 1, max(0, math.ceil(p * len(s)) - 1))
    return s[idx]


def mb(n: float | int | None) -> float | None:
    if n is None:
        return None
    return round(n / 1024 / 1024, 1)


def infer_os(path: Path, data: dict) -> str:
    env = data.get("environment") or {}
    fam = (env.get("osFamily") or "").lower()
    if fam in ("linux", "windows", "macos"):
        return fam
    runner = (env.get("runnerOs") or "").lower()
    if "linux" in runner:
        return "linux"
    if "windows" in runner:
        return "windows"
    if "mac" in runner:
        return "macos"
    joined = "/".join(path.parts).lower()
    if "ubuntu" in joined or "linux" in joined:
        return "linux"
    if "windows" in joined:
        return "windows"
    if "macos" in joined or "darwin" in joined:
        return "macos"
    # Benches historically ran only on ubuntu GitHub runners.
    if str(data.get("scenario", "")).startswith("bench-"):
        return "linux"
    return "unknown"


def infer_player(path: Path, data: dict) -> str:
    player = (data.get("player") or "").lower()
    if player in ("ffmpeg", "gstreamer", "vlc", "recording"):
        return player
    for part in path.parts:
        m = re.search(r"(ffmpeg|gstreamer|vlc|recording)", part.lower())
        if m:
            return m.group(1)
    return "unknown"


def steady_samples(samples: list[dict]) -> list[dict]:
    if not samples:
        return []
    t_max = max(float(s.get("t") or 0) for s in samples)
    end = max(WARMUP_SEC, t_max - COOLDOWN_SEC)
    return [s for s in samples if WARMUP_SEC <= float(s.get("t") or 0) <= end]


def derive(data: dict, path: Path) -> dict[str, Any]:
    samples = data.get("samples") or []
    steady = steady_samples(samples)
    wall_ms = float(data.get("wallMillis") or 0)
    wall_s = wall_ms / 1000.0 if wall_ms else 0.0
    frames_ok = int(data.get("framesOk") or 0)
    frames_fail = int(data.get("framesFail") or 0)
    target = float(data.get("targetFps") or 30)
    budget = float(data.get("frameBudgetMs") or (1000.0 / target if target else 33.33))

    fps_series = [float(s["fps"]) for s in steady if s.get("fps") is not None and float(s["fps"]) > 0]
    jvm_cpu = [float(s.get("jvmCpuPercent") or 0) for s in steady]
    child_cpu = [float(s.get("childCpuPercent") or 0) for s in steady]
    total_cpu = [a + b for a, b in zip(jvm_cpu, child_cpu)]
    jvm_rss = [float(s.get("jvmRssBytes") or 0) for s in steady]
    child_rss = [float(s.get("childRssBytes") or 0) for s in steady]
    total_rss = [a + b for a, b in zip(jvm_rss, child_rss)]
    write_p50_s = [float(s.get("latencyP50Ms") or 0) for s in steady]
    write_p95_s = [float(s.get("latencyP95Ms") or 0) for s in steady]
    write_p99_s = [float(s.get("latencyP99Ms") or 0) for s in steady]

    write = data.get("writeLatencyMs") or {}
    interval = data.get("frameIntervalMs") or {}

    sustained = (frames_ok / wall_s) if wall_s > 0 else None
    avg_fps = mean(fps_series)
    stalls = int(data.get("stalls") or 0)
    slow = int(data.get("slowWrites") or 0)
    fail_pct = (100.0 * frames_fail / max(1, frames_ok + frames_fail))

    # Jank proxy: share of 1s samples with FPS below 90% of target (when we have fps).
    jank_pct = None
    if fps_series and target > 0:
        jank_pct = 100.0 * sum(1 for f in fps_series if f < 0.9 * target) / len(fps_series)

    player = infer_player(path, data)
    os_name = infer_os(path, data)
    env = data.get("environment") or {}

    return {
        "schemaVersion": data.get("schemaVersion", 1),
        "scenario": data.get("scenario"),
        "player": player,
        "os": os_name,
        "kind": "bench" if str(data.get("scenario", "")).startswith("bench-") else "playback",
        "startedAt": data.get("startedAt"),
        "wallSec": round(wall_s, 1),
        "targetFps": target,
        "frameBudgetMs": round(budget, 2),
        "environment": {
            "osName": env.get("osName") or os_name,
            "osArch": env.get("osArch"),
            "javaVersion": env.get("javaVersion"),
            "availableProcessors": env.get("availableProcessors"),
            "runnerOs": env.get("runnerOs"),
            "osFamily": os_name,
        },
        "fps": {
            "sustained": round(sustained, 2) if sustained is not None else None,
            "avg": round(avg_fps, 2) if avg_fps is not None else None,
            "p50": round(pct(fps_series, 0.50) or 0, 2) if fps_series else None,
            "p95": round(pct(fps_series, 0.95) or 0, 2) if fps_series else None,
            "min": round(min(fps_series), 2) if fps_series else None,
        },
        "frames": {
            "ok": frames_ok,
            "fail": frames_fail,
            "failPct": round(fail_pct, 3),
            "decoded": None,  # not measured
            "rendered": None,  # not measured
            "dropped": None,  # not measured (fail ≠ decoder drop)
        },
        "writeLatencyMs": {
            "p50": write.get("p50"),
            "p95": write.get("p95"),
            "p99": write.get("p99"),
            "max": write.get("max"),
            "note": "Sink write / pipe backpressure — not end-to-end AirPlay latency",
        },
        "frameIntervalMs": {
            "p50": interval.get("p50"),
            "p95": interval.get("p95"),
            "p99": interval.get("p99"),
            "max": interval.get("max"),
            "note": "Gap between onVideo calls; N/A on schema v1 artifacts",
        },
        "latencyE2E": None,  # intentionally absent
        "cpu": {
            "avg": round(mean(total_cpu) or 0, 2) if total_cpu else None,
            "p95": round(pct(total_cpu, 0.95) or 0, 2) if total_cpu else None,
            "peak": round(max(total_cpu), 2) if total_cpu else None,
            "jvmAvg": round(mean(jvm_cpu) or 0, 2) if jvm_cpu else None,
            "childAvg": round(mean(child_cpu) or 0, 2) if child_cpu else None,
        },
        "memory": {
            "rssAvgMb": mb(mean(total_rss)) if total_rss else None,
            "rssPeakMb": mb(max(total_rss) if total_rss else data.get("rssHighWaterBytes")),
            "rssHighWaterMb": mb(data.get("rssHighWaterBytes")),
            "heapUsedMb": mb(data.get("heapUsedBytes")),
            "jvmRssAvgMb": mb(mean(jvm_rss)) if jvm_rss else None,
            "childRssAvgMb": mb(mean(child_rss)) if child_rss else None,
        },
        "stability": {
            "stalls": stalls,
            "slowWrites": slow,
            "jankPct": round(jank_pct, 2) if jank_pct is not None else None,
            "longestStallMs": interval.get("max") if interval.get("max") is not None else write.get("max"),
        },
        "startup": {
            "timeToFirstFrameMs": data.get("timeToFirstFrameMs"),
            "playerInitMs": None,
            "timeToStableMs": None,
        },
        # Full timeline for charts; summary cards still use steady_samples() (warmup trimmed).
        "series": _series_from(samples),
        "rawFile": path.name,
        "notes": data.get("notes"),
    }


def _series_from(samples: list[dict]) -> dict:
    return {
        "t": [s.get("t") for s in samples],
        "fps": [s.get("fps") for s in samples],
        "cpu": [
            float(s.get("jvmCpuPercent") or 0) + float(s.get("childCpuPercent") or 0)
            for s in samples
        ],
        "rssMb": [
            round(
                (float(s.get("jvmRssBytes") or 0) + float(s.get("childRssBytes") or 0)) / 1024 / 1024,
                2,
            )
            for s in samples
        ],
        "writeP50": [s.get("latencyP50Ms") for s in samples],
        "writeP95": [s.get("latencyP95Ms") for s in samples],
        "writeP99": [s.get("latencyP99Ms") for s in samples],
    }



def badges_for(rows: list[dict]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {r["player"]: [] for r in rows}
    if not rows:
        return out

    def award(key_fn, higher: bool, label: str) -> None:
        vals = [(r["player"], key_fn(r)) for r in rows if key_fn(r) is not None]
        if not vals:
            return
        best_v = max(v for _, v in vals) if higher else min(v for _, v in vals)
        for player, v in vals:
            if v == best_v:
                out[player].append(label)

    award(lambda r: (r.get("fps") or {}).get("sustained"), True, "Best FPS")
    award(lambda r: (r.get("cpu") or {}).get("avg"), False, "Lowest CPU")
    award(lambda r: (r.get("memory") or {}).get("rssPeakMb"), False, "Lowest memory")
    award(lambda r: (r.get("writeLatencyMs") or {}).get("p99"), False, "Lowest write p99")
    award(lambda r: (r.get("stability") or {}).get("stalls"), False, "Fewest stalls")
    award(lambda r: (r.get("frames") or {}).get("failPct"), False, "Fewest failed writes")
    return out


def collect_metrics(src: Path) -> tuple[list[dict], list[tuple[Path, dict]]]:
    derived: list[dict] = []
    raw_files: list[tuple[Path, dict]] = []
    for path in sorted(src.rglob("*.json")):
        if path.name in ("history.json", "summary.json", "dashboard.json"):
            continue
        if "problems-report" in path.name or path.name.endswith("-history.json"):
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if "scenario" not in data or "samples" not in data:
            continue
        raw_files.append((path, data))
        derived.append(derive(data, path))
    return derived, raw_files


def delta(cur: float | None, prev: float | None, higher_is_better: bool) -> dict | None:
    if cur is None or prev is None or prev == 0:
        return None
    change = 100.0 * (cur - prev) / abs(prev)
    improved = change > 0 if higher_is_better else change < 0
    return {
        "current": cur,
        "previous": prev,
        "pct": round(change, 1),
        "improved": improved,
    }


def compare_previous(current: list[dict], previous: list[dict] | None) -> dict:
    if not previous:
        return {}
    prev_map = {(p["os"], p["player"], p["kind"]): p for p in previous}
    out = {}
    for row in current:
        key = (row["os"], row["player"], row["kind"])
        prev = prev_map.get(key)
        if not prev:
            continue
        out[f"{row['os']}/{row['player']}"] = {
            "fps": delta((row.get("fps") or {}).get("sustained"), (prev.get("fps") or {}).get("sustained"), True),
            "cpu": delta((row.get("cpu") or {}).get("avg"), (prev.get("cpu") or {}).get("avg"), False),
            "rss": delta(
                (row.get("memory") or {}).get("rssPeakMb"),
                (prev.get("memory") or {}).get("rssPeakMb"),
                False,
            ),
            "stalls": delta(
                (row.get("stability") or {}).get("stalls"),
                (prev.get("stability") or {}).get("stalls"),
                False,
            ),
            "writeP95": delta(
                (row.get("writeLatencyMs") or {}).get("p95"),
                (prev.get("writeLatencyMs") or {}).get("p95"),
                False,
            ),
        }
    return out


def build_dashboard(metrics: list[dict], previous_summary: dict | None) -> dict:
    benches = [m for m in metrics if m.get("kind") == "bench"]
    playback = [m for m in metrics if m.get("kind") != "bench"]
    by_os: dict[str, list[dict]] = {}
    for m in benches or metrics:
        by_os.setdefault(m["os"], []).append(m)

    os_sections = []
    for os_name, rows in sorted(by_os.items()):
        os_sections.append(
            {
                "os": os_name,
                "badges": badges_for(rows),
                "players": rows,
            }
        )

    prev_players = None
    if previous_summary:
        prev_players = []
        for sec in previous_summary.get("osSections") or []:
            prev_players.extend(sec.get("players") or [])

    return {
        "run": {
            "number": RUN_NUMBER,
            "id": RUN_ID,
            "sha": SHA,
            "ref": REF,
            "when": datetime.now(timezone.utc).isoformat(),
            "actionsUrl": f"{SERVER_URL}/{REPO}/actions/runs/{RUN_ID}" if REPO else None,
        },
        "instrumentation": {
            "available": [
                "sustained/instantaneous FPS (pushed frames)",
                "framesOk / framesFail (failed writes)",
                "writeLatencyMs percentiles (sink backpressure)",
                "frameIntervalMs percentiles (schema v2+)",
                "stalls / slowWrites",
                "JVM+child CPU % samples",
                "JVM+child RSS / heap",
                "timeToFirstFrameMs (schema v2+)",
                "GC time in samples",
            ],
            "missing": [
                "end-to-end AirPlay display latency",
                "decoded / rendered frame counts",
                "decoder dropped-frame counters",
                "player init / time-to-stable",
                "raw per-frame histogram dump",
            ],
        },
        "osSections": os_sections,
        "playbackSmoke": playback,
        "vsPrevious": compare_previous(benches or metrics, prev_players),
        "previousRun": (previous_summary or {}).get("run"),
    }


def render_html(dashboard: dict) -> str:
    payload = json.dumps(dashboard, ensure_ascii=False)
    # Escape for embedding in <script type="application/json">
    payload = payload.replace("<", "\\u003c")
    return DASHBOARD_HTML.replace("__DASHBOARD_JSON__", payload)


DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>AirPlay playback dashboard</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.8/dist/chart.umd.min.js"></script>
<style>
:root {
  color-scheme: dark light;
  --bg: #0f1419; --panel: #1a222c; --text: #e7ecf1; --muted: #8b9aab;
  --line: #2a3542; --accent: #3dd6c6; --good: #3ecf8e; --warn: #f0a202; --bad: #ff6b6b;
  --chip: #243041;
}
@media (prefers-color-scheme: light) {
  :root {
    --bg:#f4f6f8; --panel:#fff; --text:#1a222c; --muted:#5b6b7c;
    --line:#d7dee7; --accent:#0d9488; --chip:#eef2f6;
  }
}
*{box-sizing:border-box}
body{margin:0;padding:24px;font-family:ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif;background:var(--bg);color:var(--text);line-height:1.45}
a{color:var(--accent)}
h1{margin:0 0 6px;font-size:1.55rem;font-weight:650}
h2{margin:28px 0 12px;font-size:1.15rem}
h3{margin:0 0 8px;font-size:1rem}
.sub,.muted{color:var(--muted)}
.header-meta{display:flex;flex-wrap:wrap;gap:8px 14px;margin:10px 0 18px;font-size:.9rem;color:var(--muted)}
.chip{display:inline-flex;align-items:center;gap:6px;background:var(--chip);border:1px solid var(--line);border-radius:999px;padding:4px 10px;font-size:.8rem}
.grid{display:grid;gap:14px}
.cards{grid-template-columns:repeat(auto-fit,minmax(220px,1fr))}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px 16px}
.card.best{border-color:var(--accent);box-shadow:0 0 0 1px color-mix(in srgb, var(--accent) 35%, transparent)}
.card .name{font-size:1.1rem;font-weight:650;margin-bottom:4px;text-transform:capitalize}
.card .metrics{display:grid;grid-template-columns:1fr 1fr;gap:8px 12px;margin-top:10px;font-variant-numeric:tabular-nums}
.card .m label{display:block;font-size:.7rem;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
.card .m b{font-size:.95rem;font-weight:600}
.badges{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}
.badge{font-size:.72rem;padding:3px 8px;border-radius:999px;background:color-mix(in srgb, var(--good) 18%, var(--panel));color:var(--good);border:1px solid color-mix(in srgb, var(--good) 35%, var(--line))}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px}
.panel h3{color:var(--muted);font-weight:550}
.charts{grid-template-columns:repeat(auto-fit,minmax(320px,1fr))}
canvas{width:100%!important;max-height:280px}
table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums;font-size:.9rem}
th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left}
th{color:var(--muted);font-size:.75rem;text-transform:uppercase;font-weight:550}
.na{color:var(--muted);font-style:italic}
.good{color:var(--good)} .warn{color:var(--warn)} .bad{color:var(--bad)}
.callout{background:var(--panel);border-left:3px solid var(--warn);padding:10px 14px;border-radius:8px;margin:12px 0;color:var(--muted);font-size:.9rem}
details{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:10px 14px;margin-top:12px}
summary{cursor:pointer;font-weight:600}
.delta-up.good,.delta-down.good{color:var(--good)}
.delta-up.bad,.delta-down.bad{color:var(--bad)}
.os-title{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap}
</style>
</head>
<body>
<header>
  <h1 id="title">AirPlay playback dashboard</h1>
  <div class="header-meta" id="meta"></div>
</header>
<main id="main"></main>
<script id="dashboard-data" type="application/json">__DASHBOARD_HTML_PLACEHOLDER__</script>
<script>
const D = JSON.parse(document.getElementById('dashboard-data').textContent);
const na = (v, suffix='') => (v===null||v===undefined||Number.isNaN(v)) ? '<span class="na">N/A</span>' : `${v}${suffix}`;
const fmt = (v, d=2) => (v===null||v===undefined) ? null : Number(v).toFixed(d).replace(/\.00$/,'');

document.getElementById('title').textContent = `AirPlay playback · run #${D.run.number}`;
document.getElementById('meta').innerHTML = [
  chip(D.run.ref), chip(D.run.sha),
  D.run.actionsUrl ? `<a class="chip" href="${D.run.actionsUrl}">Actions</a>` : '',
  `<a class="chip" href="../index.html">All runs</a>`,
  chip(D.run.when?.replace('T',' ').slice(0,19)+' UTC'),
  D.previousRun ? chip('vs #'+D.previousRun.number) : ''
].join('');

function chip(t){ return t ? `<span class="chip">${t}</span>` : ''; }

function deltaHtml(d){
  if(!d) return '';
  const arrow = d.pct>0 ? '▲' : (d.pct<0 ? '▼' : '•');
  const cls = d.improved ? 'good' : 'bad';
  const dir = d.pct>0 ? 'delta-up' : 'delta-down';
  return ` <span class="${dir} ${cls}">${arrow} ${d.pct>0?'+':''}${d.pct}%</span>`;
}

const tickColor = '#8b9aab';
const gridColor = '#2a3542';
const textColor = getComputedStyle(document.documentElement).getPropertyValue('--text').trim() || '#e7ecf1';

function nums(arr){
  return (arr||[]).map(Number).filter(v => Number.isFinite(v));
}

function percentile(sorted, p){
  if(!sorted.length) return 0;
  const idx = Math.min(sorted.length-1, Math.max(0, Math.ceil(p*sorted.length)-1));
  return sorted[idx];
}

/** Cap Y so rare spikes do not flatten the rest of the series. */
function robustAxisMax(values, {floor=0.05, pad=1.2}={}){
  const xs = nums(values).filter(v => v >= 0).sort((a,b)=>a-b);
  if(!xs.length) return {max: floor, peak: 0, clipped: false};
  const peak = xs[xs.length-1];
  const p95 = percentile(xs, 0.95);
  const p99 = percentile(xs, 0.99);
  let cap = Math.max(p99, p95 * 1.5, floor);
  const clipped = peak > cap * 2.5;
  if(!clipped) cap = Math.max(peak, floor);
  return {max: Math.max(cap * pad, floor), peak, clipped};
}

function niceCeil(v, step){
  if(!Number.isFinite(v) || v <= 0) return step;
  return Math.ceil(v / step) * step;
}

/** Fill transient 0% CPU holes (MXBean noise), then light EMA for readability. */
function smoothCpu(arr){
  const out = [];
  let last = null;
  for(const raw of (arr||[])){
    let v = Number(raw);
    if(!Number.isFinite(v) || v < 0) v = last ?? 0;
    // Treat isolated zeros as missing when neighbours are busy.
    if(v === 0 && last != null && last > 0.5) v = last;
    if(last == null) last = v;
    else last = 0.35 * v + 0.65 * last;
    out.push(Math.round(last * 1000) / 1000);
  }
  return out;
}

function lineOpts(y){
  return {
    responsive:true, animation:false,
    interaction:{ mode:'index', intersect:false },
    plugins:{
      legend:{ labels:{ color:textColor } },
      tooltip:{ callbacks:{
        label(ctx){
          const v = ctx.parsed.y;
          return `${ctx.dataset.label}: ${v == null ? 'n/a' : Number(v).toFixed(3)}`;
        }
      }}
    },
    scales:{
      x:{ ticks:{ color:tickColor, maxTicksLimit:8 }, grid:{ color:gridColor } },
      y:{ min:y.min ?? 0, max:y.max, ticks:{ color:tickColor }, grid:{ color:gridColor },
          title: y.title ? { display:true, text:y.title, color:tickColor } : undefined }
    }
  };
}

function barOpts(y){
  return {
    responsive:true, animation:false,
    plugins:{ legend:{ labels:{ color:textColor } } },
    scales:{
      x:{ ticks:{ color:tickColor }, grid:{ color:gridColor } },
      y:{ min:y.min ?? 0, max:y.max, ticks:{ color:tickColor }, grid:{ color:gridColor } }
    }
  };
}

function sectionOs(sec){
  const wrap = document.createElement('section');
  const players = sec.players||[];
  const env = players[0]?.environment||{};
  wrap.innerHTML = `<div class="os-title"><h2>${sec.os}</h2>
    <div class="muted">${env.javaVersion?('Java '+env.javaVersion+' · '):''}${env.availableProcessors?(env.availableProcessors+' CPUs · '):''}${players[0]?.wallSec||'?'}s bench · target ${players[0]?.targetFps||30} FPS (budget ${players[0]?.frameBudgetMs||33.33} ms)</div></div>`;

  const cards = document.createElement('div');
  cards.className = 'grid cards';
  cards.style.marginTop = '14px';
  for(const p of players){
    const badges = (sec.badges||{})[p.player]||[];
    const key = `${p.os}/${p.player}`;
    const vs = (D.vsPrevious||{})[key]||{};
    const el = document.createElement('div');
    el.className = 'card';
    el.innerHTML = `<div class="name">${p.player}</div>
      <div class="muted" style="font-size:.8rem">${p.scenario||''}</div>
      <div class="metrics">
        <div class="m"><label>Sustained FPS</label><b>${na(p.fps?.sustained)}${deltaHtml(vs.fps)}</b></div>
        <div class="m"><label>FPS avg</label><b>${na(p.fps?.avg)}</b></div>
        <div class="m"><label>CPU avg / p95</label><b>${na(p.cpu?.avg,'%')} / ${na(p.cpu?.p95,'%')}${deltaHtml(vs.cpu)}</b></div>
        <div class="m"><label>RSS avg / peak</label><b>${na(p.memory?.rssAvgMb,' MB')} / ${na(p.memory?.rssPeakMb,' MB')}${deltaHtml(vs.rss)}</b></div>
        <div class="m"><label>Write p50/p95/p99</label><b>${na(p.writeLatencyMs?.p50)} / ${na(p.writeLatencyMs?.p95)} / ${na(p.writeLatencyMs?.p99)}</b></div>
        <div class="m"><label>Frame interval p95</label><b>${na(p.frameIntervalMs?.p95,' ms')}</b></div>
        <div class="m"><label>Stalls / slow</label><b>${na(p.stability?.stalls)} / ${na(p.stability?.slowWrites)}${deltaHtml(vs.stalls)}</b></div>
        <div class="m"><label>TTFF</label><b>${na(p.startup?.timeToFirstFrameMs,' ms')}</b></div>
      </div>
      <div class="badges">${badges.map(b=>`<span class="badge">${b}</span>`).join('')}</div>`;
    cards.appendChild(el);
  }
  wrap.appendChild(cards);

  const charts = document.createElement('div');
  charts.className = 'grid charts';
  charts.style.marginTop = '14px';
  const ids = {
    fps: `fps-${sec.os}`,
    write: `write-${sec.os}`,
    scatter: `scatter-${sec.os}`,
    mem: `mem-${sec.os}`,
  };
  charts.innerHTML = `
    <div class="panel"><h3>FPS · ${sec.os}</h3><canvas id="${ids.fps}"></canvas></div>
    <div class="panel"><h3>Write latency p50/p95/p99 (ms) · ${sec.os} · lower better</h3><canvas id="${ids.write}"></canvas></div>
    <div class="panel"><h3>CPU vs FPS · ${sec.os}</h3><canvas id="${ids.scatter}"></canvas></div>
    <div class="panel"><h3>Memory RSS (MB) · ${sec.os}</h3><canvas id="${ids.mem}"></canvas></div>`;
  wrap.appendChild(charts);

  for(const p of players){
    const panel = document.createElement('details');
    panel.open = players.length <= 3;
    const cid = `ts-${sec.os}-${p.player}`;
    panel.innerHTML = `<summary>Time series · ${p.player} · ${sec.os}</summary>
      <div class="grid charts" style="margin-top:10px">
        <div class="panel"><h3>FPS over time</h3><canvas id="${cid}-fps"></canvas></div>
        <div class="panel"><h3>CPU over time <span class="muted" style="font-weight:400">(smoothed)</span></h3><canvas id="${cid}-cpu"></canvas></div>
        <div class="panel"><h3>RSS over time</h3><canvas id="${cid}-rss"></canvas></div>
        <div class="panel"><h3>Write latency over time</h3><p class="muted" id="${cid}-w-note" style="margin:0 0 8px;font-size:.8rem"></p><canvas id="${cid}-w"></canvas></div>
      </div>`;
    wrap.appendChild(panel);
    queueMicrotask(()=>{
      const s = p.series||{};
      const target = p.targetFps || 30;
      const fpsAxisMax = niceCeil(Math.max(target * 1.25, ...nums(s.fps), 1), 5);
      new Chart(document.getElementById(`${cid}-fps`), {type:'line', data:{labels:s.t, datasets:[
        {label:'fps', data:s.fps, borderColor:'#3dd6c6', pointRadius:0, borderWidth:1.5, tension:0.15},
        {label:'target', data:(s.t||[]).map(()=>target), borderColor:'#8b9aab', borderDash:[4,4], pointRadius:0, borderWidth:1}
      ]}, options:lineOpts({ min:0, max:fpsAxisMax, title:'FPS' })});

      const cpuSmooth = smoothCpu(s.cpu);
      const cpuMax = niceCeil(Math.max(5, ...nums(cpuSmooth), ...nums(s.cpu)), 5);
      new Chart(document.getElementById(`${cid}-cpu`), {type:'line', data:{labels:s.t, datasets:[
        {label:'cpu % (raw)', data:s.cpu, borderColor:'rgba(240,162,2,0.25)', pointRadius:0, borderWidth:1},
        {label:'cpu % (smooth)', data:cpuSmooth, borderColor:'#f0a202', pointRadius:0, borderWidth:1.8, tension:0.2}
      ]}, options:lineOpts({ min:0, max:cpuMax, title:'CPU %' })});

      const rssMax = niceCeil(Math.max(16, ...nums(s.rssMb)), 16);
      new Chart(document.getElementById(`${cid}-rss`), {type:'line', data:{labels:s.t, datasets:[
        {label:'rss MB', data:s.rssMb, borderColor:'#7aa2f7', pointRadius:0, borderWidth:1.5, tension:0.15, fill:false}
      ]}, options:lineOpts({ min:0, max:rssMax, title:'MB' })});

      const writeVals = [...nums(s.writeP50), ...nums(s.writeP95), ...nums(s.writeP99)];
      const wAxis = robustAxisMax(writeVals, {floor:0.05, pad:1.25});
      const note = document.getElementById(`${cid}-w-note`);
      if(wAxis.clipped){
        note.textContent = `Y-axis capped at ${wAxis.max.toFixed(2)} ms so outliers do not flatten the chart (series peak ${wAxis.peak.toFixed(1)} ms).`;
      } else {
        note.textContent = 'Sink write / backpressure latency (not end-to-end display latency).';
      }
      new Chart(document.getElementById(`${cid}-w`), {type:'line', data:{labels:s.t, datasets:[
        {label:'p50', data:s.writeP50, borderColor:'#3dd6c6', pointRadius:0, borderWidth:1.2, tension:0.15},
        {label:'p95', data:s.writeP95, borderColor:'#f0a202', pointRadius:0, borderWidth:1.2, tension:0.15},
        {label:'p99', data:s.writeP99, borderColor:'#ff6b6b', pointRadius:0, borderWidth:1.2, tension:0.15},
      ]}, options:lineOpts({ min:0, max:wAxis.max, title:'ms' })});
    });
  }

  queueMicrotask(()=>{
    const labels = players.map(p=>p.player);
    const fpsMax = niceCeil(Math.max(players[0]?.targetFps || 30, ...players.map(p=>p.fps?.sustained||0)) * 1.15, 5);
    new Chart(document.getElementById(ids.fps), {type:'bar', data:{labels, datasets:[
      {label:'sustained FPS', data:players.map(p=>p.fps?.sustained), backgroundColor:'#3dd6c6'}
    ]}, options:barOpts({ min:0, max:fpsMax })});

    // Omit raw max from comparison bars — a single stall spike (seconds) hides p50/p95/p99.
    const writeSeries = players.flatMap(p => [p.writeLatencyMs?.p50, p.writeLatencyMs?.p95, p.writeLatencyMs?.p99]);
    const wBar = robustAxisMax(writeSeries, {floor:0.05, pad:1.3});
    new Chart(document.getElementById(ids.write), {type:'bar', data:{labels, datasets:[
      {label:'p50', data:players.map(p=>p.writeLatencyMs?.p50), backgroundColor:'#3dd6c6'},
      {label:'p95', data:players.map(p=>p.writeLatencyMs?.p95), backgroundColor:'#f0a202'},
      {label:'p99', data:players.map(p=>p.writeLatencyMs?.p99), backgroundColor:'#ff6b6b'},
    ]}, options:barOpts({ min:0, max:wBar.max })});

    const cpuScatterMax = niceCeil(Math.max(5, ...players.map(p=>p.cpu?.avg||0)) * 1.4, 5);
    new Chart(document.getElementById(ids.scatter), {type:'scatter', data:{datasets: players.map((p,i)=>({
      label:p.player,
      data:[{x:p.cpu?.avg, y:p.fps?.sustained}],
      backgroundColor:['#3dd6c6','#f0a202','#7aa2f7'][i%3],
      pointRadius:8
    }))}, options:{
      responsive:true, animation:false,
      plugins:{ legend:{ labels:{ color:textColor } } },
      scales:{
        x:{ min:0, max:cpuScatterMax, title:{display:true,text:'CPU % (avg)',color:tickColor}, ticks:{color:tickColor}, grid:{color:gridColor} },
        y:{ min:0, max:fpsMax, title:{display:true,text:'FPS',color:tickColor}, ticks:{color:tickColor}, grid:{color:gridColor} }
      }
    }});

    const memMax = niceCeil(Math.max(32, ...players.map(p=>p.memory?.rssPeakMb||0)), 32);
    new Chart(document.getElementById(ids.mem), {type:'bar', data:{labels, datasets:[
      {label:'avg RSS', data:players.map(p=>p.memory?.rssAvgMb), backgroundColor:'#7aa2f7'},
      {label:'peak RSS', data:players.map(p=>p.memory?.rssPeakMb), backgroundColor:'#a78bfa'},
    ]}, options:barOpts({ min:0, max:memMax })});
  });

  return wrap;
}

const main = document.getElementById('main');
for(const sec of (D.osSections||[])) main.appendChild(sectionOs(sec));

if((D.playbackSmoke||[]).length){
  const d = document.createElement('details');
  d.innerHTML = `<summary>Playback smoke (${D.playbackSmoke.length})</summary>
    <table><thead><tr><th>OS</th><th>Player</th><th>Wall</th><th>Frames</th><th>Stalls</th></tr></thead>
    <tbody>${D.playbackSmoke.map(p=>`<tr><td>${p.os}</td><td>${p.player}</td><td>${p.wallSec}s</td><td>${p.frames?.ok}/${p.frames?.fail}</td><td>${p.stability?.stalls}</td></tr>`).join('')}</tbody></table>`;
  main.appendChild(d);
}

const details = document.createElement('details');
details.innerHTML = `<summary>Instrumentation &amp; raw details</summary>
  <p class="muted"><b>Measured:</b> ${(D.instrumentation.available||[]).join('; ')}</p>
  <p class="muted"><b>Not measured (shown as N/A):</b> ${(D.instrumentation.missing||[]).join('; ')}</p>
  <p class="muted">Per-player JSON files are published next to this page. End-to-end latency is omitted from ranking.</p>`;
main.appendChild(details);
</script>
</body>
</html>
""".replace("__DASHBOARD_HTML_PLACEHOLDER__", "__DASHBOARD_JSON__")


def load_previous_summary(out: Path, history: list) -> dict | None:
    """Local keep_files copy first, then live GitHub Pages."""
    for h in history:
        n = str(h.get("runNumber"))
        if n == str(RUN_NUMBER):
            continue
        path = out / "runs" / n / "summary.json"
        if path.is_file():
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                continue
        if not REPO:
            continue
        owner, _, name = REPO.partition("/")
        url = f"https://{owner}.github.io/{name}/runs/{n}/summary.json"
        try:
            import urllib.request

            with urllib.request.urlopen(url, timeout=10) as resp:  # noqa: S310
                return json.loads(resp.read().decode("utf-8"))
        except Exception:
            continue
    return None


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    run_dir = OUT / "runs" / str(RUN_NUMBER)
    run_dir.mkdir(parents=True, exist_ok=True)

    metrics, raw_files = collect_metrics(ROOT)
    for path, raw in raw_files:
        (run_dir / path.name).write_text(json.dumps(raw, indent=2), encoding="utf-8")

    history_path = OUT / "history.json"
    history: list = []
    if history_path.exists():
        try:
            history = json.loads(history_path.read_text(encoding="utf-8"))
        except Exception:
            history = []

    previous = load_previous_summary(OUT, history)
    dashboard = build_dashboard(metrics, previous)
    # summary.json is for regression compare — drop bulky time series
    summary_light = json.loads(json.dumps(dashboard))
    for sec in summary_light.get("osSections") or []:
        for p in sec.get("players") or []:
            p.pop("series", None)
    (run_dir / "summary.json").write_text(json.dumps(summary_light, indent=2), encoding="utf-8")
    (run_dir / "index.html").write_text(render_html(dashboard), encoding="utf-8")

    entry = {
        "runNumber": str(RUN_NUMBER),
        "runId": RUN_ID,
        "sha": SHA,
        "ref": REF,
        "when": dashboard["run"]["when"],
        "scenarios": len(metrics),
        "url": f"runs/{RUN_NUMBER}/index.html",
    }
    history = [h for h in history if str(h.get("runNumber")) != str(RUN_NUMBER)]
    history.insert(0, entry)
    history = history[:50]
    history_path.write_text(json.dumps(history, indent=2), encoding="utf-8")

    index_rows = "".join(
        f'<tr><td><a href="{h["url"]}">#{h["runNumber"]}</a></td>'
        f'<td>{h.get("ref","")}</td><td><code>{h.get("sha","")}</code></td>'
        f'<td>{h.get("scenarios",0)}</td><td>{h.get("when","")}</td></tr>'
        for h in history
    )
    (OUT / "index.html").write_text(
        f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>AirPlay CI reports</title>
<style>
:root {{ color-scheme: dark light; --bg:#0f1419; --panel:#1a222c; --text:#e7ecf1; --muted:#8b9aab; --line:#2a3542; --accent:#3dd6c6; }}
@media (prefers-color-scheme: light) {{ :root {{ --bg:#f4f6f8; --panel:#fff; --text:#1a222c; --muted:#5b6b7c; --line:#d7dee7; --accent:#0d9488; }} }}
body{{margin:0;padding:24px;font-family:ui-sans-serif,system-ui,sans-serif;background:var(--bg);color:var(--text)}}
a{{color:var(--accent)}} table{{width:100%;border-collapse:collapse;background:var(--panel);border-radius:12px;overflow:hidden}}
th,td{{padding:10px 12px;border-bottom:1px solid var(--line);text-align:left}}
th{{color:var(--muted);font-size:.8rem;text-transform:uppercase}}
</style></head><body>
<h1>AirPlay CI reports</h1>
<p style="color:var(--muted)">Playback / bench dashboards from GitHub Actions. Compare players within the same OS.</p>
<table><thead><tr><th>Run</th><th>Branch</th><th>SHA</th><th>Scenarios</th><th>When (UTC)</th></tr></thead>
<tbody>{index_rows}</tbody></table>
</body></html>
""",
        encoding="utf-8",
    )
    print(f"Wrote {len(metrics)} scenarios for run #{RUN_NUMBER} → {OUT}")


if __name__ == "__main__":
    main()
