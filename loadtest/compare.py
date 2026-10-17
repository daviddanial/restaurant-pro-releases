#!/usr/bin/env python3
"""Before/after table: run 2 (results_run2/, old viewer + server) vs run 3 (results/, after the fixes).
   python3 -I loadtest/compare.py   -> prints the table, writes loadtest/results/compare.json"""
import json
import os
import statistics as st

HERE = os.path.dirname(os.path.abspath(__file__))
RUNS = {"before": os.path.join(HERE, "results_run2"), "after": os.path.join(HERE, "results")}


def load(d, name):
    p = os.path.join(d, name)
    return json.load(open(p)) if os.path.exists(p) else None


def med(xs):
    xs = [x for x in xs if x is not None]
    return round(st.median(xs) / 1000, 2) if xs else None


def mx(xs):
    xs = [x for x in xs if x is not None]
    return round(max(xs) / 1000, 2) if xs else None


def scenario(d, label):
    sim = load(d, f"{label}.json")
    br = load(d, f"{label}.browser.json")
    if not sim and not br:
        return None
    out = {"errors": 0}
    if sim:
        s, per = sim["summary"], sim["summary"]["perReader"]
        out.update(
            sim_n=s["readers"],
            sim_first_p50=med(r["firstImageMs"] for r in per), sim_first_max=mx(r["firstImageMs"] for r in per),
            sim_full_raw=med(r["fullStudyMs"] for r in per if not r["compressed"]),
            sim_full_jls=med(r["fullStudyMs"] for r in per if r["compressed"]),
            sim_full_max=mx(r["fullStudyMs"] for r in per),
            wire_mb_raw=med([r.get("wireMb", r["mb"]) * 1000 for r in per if not r["compressed"]]),
            wire_mb_jls=med([r.get("wireMb", r["mb"]) * 1000 for r in per if r["compressed"]]),
        )
        out["errors"] += s["errors"]
    if br:
        per = [r for r in br["perReader"] if r.get("firstImageMs") is not None]
        out.update(
            br_n=len(br["perReader"]),
            br_first_max=mx(r["firstImageMs"] for r in per),
            br_preview_max=mx(r.get("previewMs") for r in per),
            br_full_max=mx(r["fullStudyMs"] for r in per),
            br_received_max=mx(r.get("allImagesReceivedMs") for r in per),
            br_step_p95=max((r["scrollP95"] or 0) for r in per) if per else None,
        )
        out["errors"] += br["errors"]
    out["n"] = out.get("sim_n", 0) + (out.get("br_n", 0) if out.get("sim_n", 0) > 1 or not sim else 0)
    if sim and br and out["sim_n"] == 1:
        out["n"] = 1  # N=1 runs one simulated reader, then one browser reader, separately
    return out


def labels():
    for n in (1, 5, 10, 20):
        for net in ("unl", "50mbps", "20mbps"):
            for temp in ("cold", "warm"):
                yield f"ct-{n}-{net}-{temp}"
    for mod in ("mr", "cr"):
        for n in (10, 20):
            yield f"{mod}-{n}-50mbps-cold"
    for c in ("ctrl-ct-1-50mbps-simonly", "ctrl-ct-10-50mbps-simonly", "ctrl-ct-20-50mbps-simonly",
              "ctrl-ct-10-unl-simonly", "ctrl-ct-20-unl-simonly"):
        yield c


def main():
    rows = []
    for label in labels():
        row = {"label": label}
        for k, d in RUNS.items():
            row[k] = scenario(d, label)
        rows.append(row)
    reopen = load(RUNS["after"], "reopen-ct-2-50mbps.browser.json")
    json.dump({"rows": rows, "reopen": reopen}, open(os.path.join(RUNS["after"], "compare.json"), "w"), indent=1)

    f = lambda v: "-" if v is None else str(v)
    print(f"{'scenario':28} {'N':>3} | {'first max s':>13} | {'sim full raw/jls s':>22} | {'br first':>11} {'br full':>13} {'br rcvd':>7} {'prev':>5} | {'err':>7}")
    for r in rows:
        b, a = r["before"] or {}, r["after"] or {}
        if not a and not b:
            continue
        print(f"{r['label']:28} {f(a.get('n') or b.get('n')):>3} | "
              f"{f(b.get('sim_first_max')):>6}>{f(a.get('sim_first_max')):<6} | "
              f"{f(b.get('sim_full_raw'))}/{f(b.get('sim_full_jls'))} > {f(a.get('sim_full_raw'))}/{f(a.get('sim_full_jls')):<6} | "
              f"{f(b.get('br_first_max')):>5}>{f(a.get('br_first_max')):<5} {f(b.get('br_full_max')):>6}>{f(a.get('br_full_max')):<6} "
              f"{f(a.get('br_received_max')):>7} {f(a.get('br_preview_max')):>5} | {f(b.get('errors')):>3}>{f(a.get('errors')):<3}")
    if reopen:
        for r in reopen["perReader"]:
            print("reopen", r["reader"], r["accession"], "first", r["firstImageMs"], "full", r["fullStudyMs"], "wire MB", r["wireMb"], "->", r["reopen"])


if __name__ == "__main__":
    main()
