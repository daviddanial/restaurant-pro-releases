#!/usr/bin/env python3
"""Summarize loadtest/results: one row per scenario (sim + browser readers) and resource usage per component.
   python3 -I loadtest/summarize.py  -> prints tables, writes loadtest/results/summary.json"""
import glob
import json
import os
import statistics as st

R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")


def monitor(label):
    path = os.path.join(R, f"{label}.monitor.jsonl")
    if not os.path.exists(path):
        return None
    rows = [json.loads(l) for l in open(path) if l.strip()]
    busy = rows[2:-2] or rows
    out = {}
    for comp in ("api", "minio", "postgres", "rabbitmq"):
        cpu = [r[comp]["cpuS"] for r in busy]
        mem = [r[comp].get("rssMB", r[comp].get("memMB", 0)) for r in busy]
        out[comp] = {
            "cpu_avg": round(st.mean(cpu), 2), "cpu_peak": round(max(cpu), 2), "mem_peak_mb": round(max(mem)),
            "disk_rd_mb": round(sum(r[comp]["rdMB"] for r in busy)), "disk_wr_mb": round(sum(r[comp]["wrMB"] for r in busy)),
        }
        if comp == "api":
            out[comp]["threads_peak"] = max(r["api"]["threads"] for r in busy)
            out[comp]["read_from_minio_mb"] = round(sum(r["api"]["rcharMB"] for r in busy))
            out[comp]["sent_mb"] = round(sum(r["api"]["wcharMB"] for r in busy))
        else:
            out[comp]["net_tx_mb"] = round(sum(r[comp]["txMB"] for r in busy))
    host = [r["host"] for r in busy]
    server = [sum(r[c]["cpuS"] for c in ("api", "minio", "postgres", "rabbitmq")) for r in busy]
    out["host"] = {
        "cpu_avg": round(st.mean(h["cpuS"] for h in host), 2), "cpu_peak": round(max(h["cpuS"] for h in host), 2),
        "ncpu": host[0]["ncpu"], "iowait_avg": round(st.mean(h["iowaitS"] for h in host), 2),
        "mem_avail_min_mb": round(min(h["memAvailMB"] for h in host)),
        "disk_rd_mb": round(sum(h["diskRdMB"] for h in host)),
        "client_cpu_avg": round(st.mean(h["cpuS"] - s for h, s in zip(host, server)), 2),
    }
    return out


def main():
    rows = []
    for f in sorted(glob.glob(os.path.join(R, "*.json"))):
        if f.endswith(".browser.json") or f.endswith("summary.json") or "smoke" in f:
            continue
        label = os.path.basename(f)[:-5]
        sim = json.load(open(f))["summary"]
        bfile = os.path.join(R, f"{label}.browser.json")
        br = json.load(open(bfile)) if os.path.exists(bfile) else None
        rows.append({"label": label, "sim": sim, "browser": br, "resources": monitor(label)})
    json.dump(rows, open(os.path.join(R, "summary.json"), "w"), indent=1)

    s = lambda ms: "-" if ms is None else f"{ms/1000:.2f}"
    print(f"{'scenario':28} {'N':>3} | {'sim first p50/max':>17} {'sim full p50/max':>17} {'scroll p50/p95/max ms':>22} {'err':>4} |"
          f" {'browser first max':>17} {'br full max':>11} {'br step p95':>11} {'br err':>6}")
    for r in rows:
        a, b = r["sim"], r["browser"]
        print(f"{r['label']:28} {a['readers'] + (3 if b and a['readers'] > 1 else 0):>3} | "
              f"{s(a['firstImage']['p50']):>8}/{s(a['firstImage']['max']):<8} {s(a['fullStudy']['p50']):>8}/{s(a['fullStudy']['max']):<8} "
              f"{a['scrollMs']['p50']:>6}/{a['scrollMs']['p95']:>6}/{a['scrollMs']['max']:>7} {a['errors']:>4} | "
              + (f"{s(b['firstImage']['max']):>17} {s(b['fullStudy']['max']):>11} {str(b['scrollMs']['p95']):>11} {b['errors']:>6}" if b else ""))
    print()
    print(f"{'scenario':28} | {'API cpu avg/pk':>14} {'API rss pk':>10} {'thr':>4} | {'MinIO cpu avg/pk':>16} {'MinIO mem':>9} {'MinIO disk rd':>13} |"
          f" {'PG cpu':>6} | {'host cpu avg/pk':>15} {'client cpu':>10} {'iowait':>6} {'mem avail min':>13}")
    for r in rows:
        m = r["resources"]
        if not m:
            continue
        print(f"{r['label']:28} | {m['api']['cpu_avg']:>6}/{m['api']['cpu_peak']:<7} {m['api']['mem_peak_mb']:>10} {m['api']['threads_peak']:>4} | "
              f"{m['minio']['cpu_avg']:>7}/{m['minio']['cpu_peak']:<8} {m['minio']['mem_peak_mb']:>9} {m['minio']['disk_rd_mb']:>13} | "
              f"{m['postgres']['cpu_avg']:>6} | {m['host']['cpu_avg']:>7}/{m['host']['cpu_peak']:<7} {m['host']['client_cpu_avg']:>10} "
              f"{m['host']['iowait_avg']:>6} {m['host']['mem_avail_min_mb']:>13}")


if __name__ == "__main__":
    main()
