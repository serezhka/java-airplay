#!/usr/bin/env python3
"""Build a GitHub Pages site from CI playback/bench metric JSON artifacts."""

from __future__ import annotations

import json
import os
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(os.environ.get("PAGES_SRC", "pages-src"))
OUT = Path(os.environ.get("PAGES_OUT", "pages-out"))
RUN_ID = os.environ.get("GITHUB_RUN_ID", "local")
RUN_NUMBER = os.environ.get("GITHUB_RUN_NUMBER", "0")
SHA = os.environ.get("GITHUB_SHA", "")[:7]
REF = os.environ.get("GITHUB_REF_NAME", "")
SERVER_URL = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
REPO = os.environ.get("GITHUB_REPOSITORY", "")


def collect_metrics(src: Path) -> list[dict]:
    metrics = []
    for path in sorted(src.rglob("*.json")):
        if path.name.endswith("-history.json") or "problems-report" in path.name:
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if "scenario" not in data or "samples" not in data:
            continue
        # Infer os/player from artifact folder names when present
        parts = path.parts
        os_name = "unknown"
        player = data.get("player") or "unknown"
        for p in parts:
            if "ubuntu" in p:
                os_name = "ubuntu"
            elif "windows" in p:
                os_name = "windows"
            m = re.search(r"(ffmpeg|gstreamer|vlc|recording)", p)
            if m:
                player = m.group(1)
        data["_os"] = os_name
        data["_player"] = player
        data["_file"] = path.name
        metrics.append(data)
    return metrics


def summary_row(m: dict) -> dict:
    lat = m.get("writeLatencyMs") or {}
    return {
        "scenario": m.get("scenario"),
        "os": m.get("_os"),
        "player": m.get("_player"),
        "wallSec": round((m.get("wallMillis") or 0) / 1000, 1),
        "framesOk": m.get("framesOk"),
        "framesFail": m.get("framesFail"),
        "p50": lat.get("p50"),
        "p95": lat.get("p95"),
        "p99": lat.get("p99"),
        "max": lat.get("max"),
        "slowWrites": m.get("slowWrites"),
        "stalls": m.get("stalls"),
        "rssMb": round((m.get("rssHighWaterBytes") or 0) / 1024 / 1024, 1),
        "file": m.get("_file"),
    }


def render_run_page(metrics: list[dict], run_dir: Path) -> str:
    rows = [summary_row(m) for m in metrics]
    charts = []
    for m in metrics:
        sid = re.sub(r"[^a-zA-Z0-9_-]", "_", m.get("scenario") or "m")
        samples = m.get("samples") or []
        charts.append(
            {
                "id": sid,
                "title": f"{m.get('_player')} · {m.get('_os')} · {m.get('scenario')}",
                "t": [s.get("t") for s in samples],
                "p50": [s.get("latencyP50Ms") for s in samples],
                "p95": [s.get("latencyP95Ms") for s in samples],
                "p99": [s.get("latencyP99Ms") for s in samples],
                "fps": [s.get("fps") for s in samples],
                "jvmRss": [round((s.get("jvmRssBytes") or 0) / 1024 / 1024, 2) for s in samples],
                "childRss": [round((s.get("childRssBytes") or 0) / 1024 / 1024, 2) for s in samples],
            }
        )

    payload = json.dumps({"rows": rows, "charts": charts}, ensure_ascii=False)
    actions = f"{SERVER_URL}/{REPO}/actions/runs/{RUN_ID}" if REPO else "#"
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>AirPlay CI run #{RUN_NUMBER}</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.8/dist/chart.umd.min.js"></script>
  <style>
    :root {{ --bg:#0f1419; --panel:#1a222c; --text:#e7ecf1; --muted:#8b9aab; --line:#2a3542; --accent:#3dd6c6; }}
    body {{ margin:0; padding:24px; font-family: ui-sans-serif, system-ui, sans-serif; background:var(--bg); color:var(--text); }}
    a {{ color:var(--accent); }}
    h1 {{ margin:0 0 8px; font-size:1.5rem; }}
    .sub {{ color:var(--muted); margin-bottom:20px; }}
    table {{ width:100%; border-collapse:collapse; background:var(--panel); border-radius:12px; overflow:hidden; }}
    th, td {{ padding:10px 12px; border-bottom:1px solid var(--line); text-align:left; font-variant-numeric:tabular-nums; }}
    th {{ color:var(--muted); font-weight:500; font-size:.8rem; text-transform:uppercase; }}
    .grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(340px,1fr)); gap:16px; margin-top:20px; }}
    .panel {{ background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:12px; }}
    .panel h2 {{ margin:0 0 8px; font-size:.95rem; color:var(--muted); font-weight:500; }}
  </style>
</head>
<body>
  <h1>AirPlay playback / bench · run #{RUN_NUMBER}</h1>
  <div class="sub">{REF} · {SHA} · <a href="{actions}">Actions run</a> · <a href="../index.html">All runs</a></div>
  <table id="tbl"><thead><tr>
    <th>OS</th><th>Player</th><th>Scenario</th><th>Wall</th><th>Frames</th>
    <th>p50</th><th>p95</th><th>p99</th><th>max</th><th>Slow</th><th>Stalls</th><th>RSS MB</th>
  </tr></thead><tbody></tbody></table>
  <div class="grid" id="charts"></div>
  <script>
    const data = {payload};
    const tb = document.querySelector('#tbl tbody');
    for (const r of data.rows) {{
      const tr = document.createElement('tr');
      tr.innerHTML = `<td>${{r.os}}</td><td>${{r.player}}</td><td>${{r.scenario}}</td>
        <td>${{r.wallSec}}s</td><td>${{r.framesOk}}/${{r.framesFail}}</td>
        <td>${{r.p50}}</td><td>${{r.p95}}</td><td>${{r.p99}}</td><td>${{r.max}}</td>
        <td>${{r.slowWrites}}</td><td>${{r.stalls}}</td><td>${{r.rssMb}}</td>`;
      tb.appendChild(tr);
    }}
    const host = document.getElementById('charts');
    for (const c of data.charts) {{
      const wrap = document.createElement('div');
      wrap.className = 'panel';
      wrap.innerHTML = `<h2>${{c.title}} · write latency</h2><canvas id="${{c.id}}-lat"></canvas>
        <h2 style="margin-top:12px">FPS / RSS</h2><canvas id="${{c.id}}-fps"></canvas>`;
      host.appendChild(wrap);
      const common = {{ responsive:true, animation:false, plugins:{{legend:{{labels:{{color:'#e7ecf1'}}}}}},
        scales:{{ x:{{ ticks:{{color:'#8b9aab'}}, grid:{{color:'#2a3542'}} }},
                  y:{{ ticks:{{color:'#8b9aab'}}, grid:{{color:'#2a3542'}} }} }} }};
      new Chart(document.getElementById(c.id+'-lat'), {{ type:'line', data:{{ labels:c.t, datasets:[
        {{ label:'p50', data:c.p50, borderColor:'#3dd6c6', pointRadius:0, borderWidth:1.5 }},
        {{ label:'p95', data:c.p95, borderColor:'#f0a202', pointRadius:0, borderWidth:1.5 }},
        {{ label:'p99', data:c.p99, borderColor:'#ff6b6b', pointRadius:0, borderWidth:1.5 }},
      ]}}, options:common }});
      new Chart(document.getElementById(c.id+'-fps'), {{ type:'line', data:{{ labels:c.t, datasets:[
        {{ label:'fps', data:c.fps, borderColor:'#3dd6c6', pointRadius:0, borderWidth:1.5, yAxisID:'y' }},
        {{ label:'jvm RSS MB', data:c.jvmRss, borderColor:'#7aa2f7', pointRadius:0, borderWidth:1.5, yAxisID:'y1' }},
        {{ label:'child RSS MB', data:c.childRss, borderColor:'#f0a202', pointRadius:0, borderWidth:1.5, yAxisID:'y1' }},
      ]}}, options:{{ ...common, scales:{{ ...common.scales, y1:{{ position:'right', ticks:{{color:'#8b9aab'}}, grid:{{drawOnChartArea:false}} }} }} }} }});
    }}
  </script>
</body>
</html>
"""


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    run_dir = OUT / "runs" / RUN_NUMBER
    run_dir.mkdir(parents=True, exist_ok=True)

    metrics = collect_metrics(ROOT)
    for m in metrics:
        src_name = m.get("_file") or "metrics.json"
        (run_dir / src_name).write_text(json.dumps(m, indent=2), encoding="utf-8")

    (run_dir / "index.html").write_text(render_run_page(metrics, run_dir), encoding="utf-8")

    history_path = OUT / "history.json"
    history = []
    if history_path.exists():
        try:
            history = json.loads(history_path.read_text(encoding="utf-8"))
        except Exception:
            history = []
    entry = {
        "runNumber": RUN_NUMBER,
        "runId": RUN_ID,
        "sha": SHA,
        "ref": REF,
        "when": datetime.now(timezone.utc).isoformat(),
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
<html lang="en"><head><meta charset="utf-8"/><title>AirPlay CI reports</title>
<style>
 body{{margin:0;padding:24px;font-family:ui-sans-serif,system-ui,sans-serif;background:#0f1419;color:#e7ecf1}}
 a{{color:#3dd6c6}} table{{width:100%;border-collapse:collapse;background:#1a222c;border-radius:12px;overflow:hidden}}
 th,td{{padding:10px 12px;border-bottom:1px solid #2a3542;text-align:left}}
 th{{color:#8b9aab;font-size:.8rem;text-transform:uppercase}}
</style></head><body>
<h1>AirPlay CI reports</h1>
<p style="color:#8b9aab">Latest playback / bench metrics from GitHub Actions.</p>
<table><thead><tr><th>Run</th><th>Branch</th><th>SHA</th><th>Scenarios</th><th>When (UTC)</th></tr></thead>
<tbody>{index_rows}</tbody></table>
</body></html>
""",
        encoding="utf-8",
    )
    print(f"Wrote {len(metrics)} scenarios for run #{RUN_NUMBER} → {OUT}")


if __name__ == "__main__":
    main()
