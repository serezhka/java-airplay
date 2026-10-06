package com.github.serezhka.airplay.player.harness;

/**
 * Self-contained HTML report with Chart.js time-series for bench/playback metrics JSON.
 */
final class BenchReportHtml {

    private BenchReportHtml() {
    }

    static String render(String metricsJson) {
        String safe = metricsJson
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("</", "<\\/");
        return TEMPLATE.replace("__METRICS_JSON__", safe.trim());
    }

    private static final String TEMPLATE = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1"/>
                  <title>AirPlay bench report</title>
                  <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.8/dist/chart.umd.min.js"></script>
                  <style>
                    :root {
                      --bg: #0f1419;
                      --panel: #1a222c;
                      --text: #e7ecf1;
                      --muted: #8b9aab;
                      --accent: #3dd6c6;
                      --warn: #f0a202;
                      --danger: #ff6b6b;
                      --line: #2a3542;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0; padding: 24px;
                      font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
                      background: radial-gradient(1200px 600px at 10% -10%, #1b2a33, var(--bg));
                      color: var(--text);
                    }
                    h1 { font-size: 1.6rem; margin: 0 0 4px; font-weight: 600; }
                    .sub { color: var(--muted); margin-bottom: 20px; }
                    .cards {
                      display: grid;
                      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
                      gap: 12px; margin-bottom: 20px;
                    }
                    .card {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 12px;
                      padding: 14px 16px;
                    }
                    .card .label { color: var(--muted); font-size: 0.75rem; text-transform: uppercase; letter-spacing: .04em; }
                    .card .value { font-size: 1.35rem; margin-top: 6px; font-variant-numeric: tabular-nums; }
                    .grid {
                      display: grid;
                      grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
                      gap: 16px;
                    }
                    .panel {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 12px;
                      padding: 12px 14px 18px;
                    }
                    .panel h2 { font-size: 0.95rem; margin: 0 0 8px; color: var(--muted); font-weight: 500; }
                    canvas { width: 100% !important; max-height: 260px; }
                  </style>
                </head>
                <body>
                  <h1 id="title">AirPlay bench</h1>
                  <div class="sub" id="subtitle"></div>
                  <div class="cards" id="cards"></div>
                  <div class="grid">
                    <div class="panel"><h2>Sink write latency (backpressure, not e2e)</h2><canvas id="cLatency"></canvas></div>
                    <div class="panel"><h2>Effective FPS</h2><canvas id="cFps"></canvas></div>
                    <div class="panel"><h2>Throughput</h2><canvas id="cTput"></canvas></div>
                    <div class="panel"><h2>Memory (RSS / heap)</h2><canvas id="cMem"></canvas></div>
                    <div class="panel"><h2>CPU</h2><canvas id="cCpu"></canvas></div>
                    <div class="panel"><h2>Stalls &amp; slow writes (≥5ms)</h2><canvas id="cStall"></canvas></div>
                    <div class="panel"><h2>GC time (cumulative)</h2><canvas id="cGc"></canvas></div>
                    <div class="panel"><h2>Failed frame writes</h2><canvas id="cFail"></canvas></div>
                  </div>
                  <script>
                    const data = JSON.parse('__METRICS_JSON__');
                    const samples = data.samples || [];
                    const t = samples.map(s => s.t);
                    const mb = b => (b / (1024 * 1024));
                    document.getElementById('title').textContent = data.scenario + ' · ' + data.player;
                    document.getElementById('subtitle').textContent =
                      (data.wallMillis / 1000).toFixed(1) + 's wall · target ' + (data.targetFps || 30) + ' fps · ' +
                      (data.startedAt || '');

                    function card(label, value) {
                      return '<div class="card"><div class="label">' + label + '</div><div class="value">' + value + '</div></div>';
                    }
                    const lat = data.writeLatencyMs || {};
                    document.getElementById('cards').innerHTML = [
                      card('Frames OK', data.framesOk ?? 0),
                      card('Frames fail', data.framesFail ?? 0),
                      card('Data pushed', mb(data.bytesPushed || 0).toFixed(1) + ' MB'),
                      card('Latency p50', (lat.p50 ?? 0).toFixed(2) + ' ms'),
                      card('Latency p95', (lat.p95 ?? 0).toFixed(2) + ' ms'),
                      card('Latency p99', (lat.p99 ?? 0).toFixed(2) + ' ms'),
                      card('Latency max', (lat.max ?? 0).toFixed(2) + ' ms'),
                      card('RSS high water', mb(data.rssHighWaterBytes || 0).toFixed(1) + ' MB'),
                      card('Slow writes', data.slowWrites ?? 0),
                      card('Stalls', data.stalls ?? 0),
                    ].join('');

                    const common = {
                      responsive: true,
                      animation: false,
                      interaction: { mode: 'index', intersect: false },
                      scales: {
                        x: { title: { display: true, text: 'time (s)', color: '#8b9aab' }, ticks: { color: '#8b9aab' }, grid: { color: '#2a3542' } },
                        y: { ticks: { color: '#8b9aab' }, grid: { color: '#2a3542' } }
                      },
                      plugins: { legend: { labels: { color: '#e7ecf1' } } }
                    };
                    function line(id, datasets, yTitle) {
                      const opts = JSON.parse(JSON.stringify(common));
                      opts.scales.y.title = { display: !!yTitle, text: yTitle || '', color: '#8b9aab' };
                      new Chart(document.getElementById(id), { type: 'line', data: { labels: t, datasets }, options: opts });
                    }
                    const ds = (label, key, color, map = v => v) => ({
                      label, data: samples.map(s => map(s[key])),
                      borderColor: color, backgroundColor: color + '33',
                      borderWidth: 1.5, pointRadius: 0, tension: 0.15
                    });

                    line('cLatency', [
                      ds('p50 ms', 'latencyP50Ms', '#3dd6c6'),
                      ds('p95 ms', 'latencyP95Ms', '#f0a202'),
                      ds('p99 ms', 'latencyP99Ms', '#ff6b6b'),
                    ], 'ms');
                    line('cFps', [
                      ds('fps', 'fps', '#3dd6c6'),
                      { label: 'target', data: t.map(() => data.targetFps || 30), borderColor: '#8b9aab', borderDash: [4,4], pointRadius: 0, borderWidth: 1 }
                    ], 'fps');
                    const tput = samples.map((s, i) => {
                      if (i === 0) return 0;
                      const dt = Math.max(0.001, s.t - samples[i-1].t);
                      return ((s.bytesPushed - samples[i-1].bytesPushed) * 8) / dt / 1e6;
                    });
                    line('cTput', [{ label: 'Mbps', data: tput, borderColor: '#7aa2f7', backgroundColor: '#7aa2f733', borderWidth: 1.5, pointRadius: 0, tension: 0.15 }], 'Mbps');
                    line('cMem', [
                      ds('JVM RSS MB', 'jvmRssBytes', '#3dd6c6', mb),
                      ds('Child RSS MB', 'childRssBytes', '#f0a202', mb),
                      ds('Heap MB', 'heapUsedBytes', '#7aa2f7', mb),
                    ], 'MB');
                    line('cCpu', [
                      ds('JVM %', 'jvmCpuPercent', '#3dd6c6'),
                      ds('Child %', 'childCpuPercent', '#f0a202'),
                    ], '%');
                    line('cStall', [
                      ds('slowWrites', 'slowWrites', '#f0a202'),
                      ds('stalls', 'stalls', '#ff6b6b'),
                    ], 'count');
                    line('cGc', [ds('gcTimeMs', 'gcTimeMs', '#c792ea')], 'ms');
                    line('cFail', [ds('framesFail', 'framesFail', '#ff6b6b')], 'count');
                  </script>
                </body>
                </html>
                """;
}
