#!/usr/bin/env python3
"""Build loadtest/report.html from loadtest/results/*.json (run 2 matrix + controls)."""
import html
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(HERE, "results", "report_rows.json")))
res = {r["label"]: r for r in json.load(open(os.path.join(HERE, "results", "resources_table.json")))}
E = html.escape


def s(ms, d=1):
    return "–" if ms is None else f"{ms / 1000:.{d}f}"


def sec(x):
    return "–" if x is None else f"{x:.0f}"


NET = {"unl": "Unlimited", "50mbps": "50 Mbps", "20mbps": "20 Mbps"}

# ---------- chart: full CT study, 50 Mbps + 50 ms, simulated readers only (controls) ----------
ctrl10 = next(r for r in rows if r["lab"] == "ctrl-ct-10-50mbps-simonly")
ctrl20 = next(r for r in rows if r["lab"] == "ctrl-ct-20-50mbps-simonly")
groups = [
    ("10 readers", "measured", ctrl10["full_raw"], ctrl10["full_jls"]),
    ("20 readers", "measured", ctrl20["full_raw"], ctrl20["full_jls"]),
    ("With prefetch", "estimate", 273 * 8 / 50, 113 * 8 / 50),
]
W, LEFT, RIGHT, TOP = 640, 132, 24, 16
BAR, GAP_IN, GAP_GRP = 18, 2, 22
XMAX = 90
plot_w = W - LEFT - RIGHT
x = lambda v: LEFT + v / XMAX * plot_w
H = TOP + len(groups) * (2 * BAR + GAP_IN) + (len(groups) - 1) * GAP_GRP + 40
svg = [f'<svg viewBox="0 0 {W} {H}" role="img" aria-labelledby="chart-title chart-desc" class="chart">']
for t in range(0, XMAX + 1, 15):
    svg.append(f'<line x1="{x(t):.1f}" x2="{x(t):.1f}" y1="{TOP - 4}" y2="{H - 30}" class="grid"/>')
    svg.append(f'<text x="{x(t):.1f}" y="{H - 12}" class="tick" text-anchor="middle">{t}s</text>')
y = TOP
for name, kind, raw, jls in groups:
    gy = y
    for val, cls, label in ((raw, "s1", "Uncompressed"), (jls, "s2", "JPEG-LS lossless")):
        est = kind == "estimate"
        w = x(val) - LEFT
        svg.append(
            f'<g class="bar"><title>{E(name)} · {label}: {val:.0f} s ({"estimate" if est else "measured"})</title>'
            f'<rect x="{LEFT}" y="{y}" width="{w:.1f}" height="{BAR}" rx="4" class="{cls}{" est" if est else ""}"/>'
            f'<text x="{x(val) + 6:.1f}" y="{y + BAR / 2 + 4}" class="val">{val:.0f} s</text></g>')
        y += BAR + GAP_IN
    svg.append(f'<text x="{LEFT - 10}" y="{gy + BAR + 2}" class="glabel" text-anchor="end">{E(name)}</text>')
    svg.append(f'<text x="{LEFT - 10}" y="{gy + BAR + 16}" class="gsub" text-anchor="end">{kind}</text>')
    y += GAP_GRP - GAP_IN
svg.append(f'<line x1="{x(60):.1f}" x2="{x(60):.1f}" y1="{TOP - 8}" y2="{H - 30}" class="target"/>')
svg.append(f'<text x="{x(60) + 5:.1f}" y="{TOP + 2}" class="targetlabel">60 s target</text>')
svg.append("</svg>")
chart = "\n".join(svg)

# ---------- results table ----------
def row_html(r):
    ctrl = r["temp"] == "control"
    scen = f'{r["mod"]} · {NET[r["net"]]}{"" if r["net"] == "unl" else " + 50 ms"} · {"control, no browsers" if ctrl else r["temp"]}'
    first = f'{s(r["sim_first_p50"], 2)} / {s(r["sim_first_max"], 2)}'
    bfirst = s(r["br_first_max"], 2)
    full = f'{sec(r["full_raw"])} / {sec(r["full_jls"])}'
    bfull = s(r["br_full_max"], 0)
    scroll = f'{r["scroll_p50"]} / {r["scroll_p95"]}'
    bstep = "–" if r["br_step_p95"] is None else str(r["br_step_p95"])
    err = r["errors"]
    first_flag = ' class="miss"' if (r["br_first_max"] or r["sim_first_max"]) > 3000 and r["n"] >= 10 else ""
    return (f'<tr{" class=ctrl" if ctrl else ""}><td>{E(scen)}</td><td class="n">{r["n"]}</td>'
            f'<td class="n"{first_flag}>{first}</td><td class="n">{bfirst}</td><td class="n">{full}</td><td class="n">{bfull}</td>'
            f'<td class="n">{scroll}</td><td class="n">{bstep}</td><td class="n{" ok" if err == 0 else " bad"}">{err}</td></tr>')

order = lambda r: ({"CT": 0, "MR": 1, "CR": 2}[r["mod"]], r["temp"] == "control", {"unl": 0, "50mbps": 1, "20mbps": 2}[r["net"]], r["n"], r["temp"])
table_rows = "\n".join(row_html(r) for r in sorted(rows, key=order))

# ---------- resources table ----------
RES_ORDER = [
    ("ct-1-50mbps-cold", "CT · 1 reader · 50 Mbps"),
    ("ct-5-50mbps-cold", "CT · 5 readers · 50 Mbps"),
    ("ct-10-50mbps-cold", "CT · 10 readers · 50 Mbps"),
    ("ct-20-50mbps-cold", "CT · 20 readers · 50 Mbps"),
    ("mr-20-50mbps-cold", "MR · 20 readers · 50 Mbps"),
    ("cr-20-50mbps-cold", "CR · 20 readers · 50 Mbps"),
    ("ctrl-ct-10-50mbps-simonly", "CT · 10 · 50 Mbps · control"),
    ("ctrl-ct-20-50mbps-simonly", "CT · 20 · 50 Mbps · control"),
    ("ctrl-ct-10-unl-simonly", "CT · 10 · unlimited · control"),
    ("ctrl-ct-20-unl-simonly", "CT · 20 · unlimited · control"),
]
res_rows = "\n".join(
    f'<tr{" class=ctrl" if "control" in name else ""}><td>{E(name)}</td>'
    f'<td class="n">{res[l]["api_cpu"]}</td><td class="n">{res[l]["api_rss"]:,}</td><td class="n">{res[l]["api_thr"]}</td><td class="n">{res[l]["api_out_mbs"]}</td>'
    f'<td class="n">{res[l]["minio_cpu"]}</td><td class="n">{res[l]["minio_mem"]:,}</td><td class="n">{res[l]["minio_disk_mbs"]}</td>'
    f'<td class="n">{res[l]["pg_cpu"]}</td><td class="n">{res[l]["rabbit_cpu"]}</td>'
    f'<td class="n">{res[l]["host_cpu"]}</td><td class="n">{res[l]["client_cpu"]}</td><td class="n">{res[l]["iowait"]}</td></tr>'
    for l, name in RES_ORDER)

page = open(os.path.join(HERE, "report_template.html")).read()
page = page.replace("{{CHART}}", chart).replace("{{RESULT_ROWS}}", table_rows).replace("{{RES_ROWS}}", res_rows)
open(os.path.join(HERE, "report.html"), "w").write(page)
print("wrote", os.path.join(HERE, "report.html"), len(page), "bytes")
