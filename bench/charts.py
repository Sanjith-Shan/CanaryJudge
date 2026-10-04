"""Renders the charts in docs/ from results/*.jsonl.

Usage: python3 bench/charts.py [results_dir] [docs_dir]
Colors: the first three categorical slots of a validated palette (blue, orange, aqua), gray for references.
"""
import json
import os
import statistics
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

R = sys.argv[1] if len(sys.argv) > 1 else "results"
D = sys.argv[2] if len(sys.argv) > 2 else "docs"
BLUE, ORANGE, AQUA, GRAY = "#2a78d6", "#eb6834", "#1baf7a", "#8a8984"
INK, INK2, SURFACE, GRID = "#0b0b0b", "#52514e", "#fcfcfb", "#e6e5e1"
ORDER = ["latency_2", "latency_5", "latency_10", "latency_25", "latency_50", "errors_0.5", "errors_2",
         "leak_1k", "leak_4k", "cpu_1ms", "cpu_4ms"]
LABEL = {"latency_2": "latency +2%", "latency_5": "latency +5%", "latency_10": "latency +10%",
         "latency_25": "latency +25%", "latency_50": "latency +50%", "errors_0.5": "errors +0.5%",
         "errors_2": "errors +2%", "leak_1k": "leak 1 KB/req", "leak_4k": "leak 4 KB/req",
         "cpu_1ms": "CPU +1 ms/req", "cpu_4ms": "CPU +4 ms/req", "aa": "A/A"}

plt.rcParams.update({
    "font.family": "DejaVu Sans", "font.size": 10, "axes.edgecolor": GRID, "axes.labelcolor": INK2,
    "xtick.color": INK2, "ytick.color": INK2, "axes.titlecolor": INK, "axes.titlesize": 12,
    "axes.titleweight": "bold", "figure.facecolor": SURFACE, "axes.facecolor": SURFACE,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.spines.top": False,
    "axes.spines.right": False, "legend.frameon": False, "legend.labelcolor": INK2,
})


def load(name):
    p = os.path.join(R, name)
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return [json.loads(l) for l in f if l.strip()]


def save(fig, name):
    fig.tight_layout()
    fig.savefig(os.path.join(D, name), dpi=150)
    plt.close(fig)
    print("wrote", os.path.join(D, name))


def detection():
    exp1 = {r["scenario"]: r for r in load("exp1.jsonl")}
    rows = [s for s in ORDER if s in exp1]
    if not rows:
        return
    judges = [("sequential", "sequential judge", BLUE), ("kayenta_style", "Kayenta-style judge", ORANGE),
              ("static_tuned", "static limits (tuned)", AQUA)]
    fig, ax = plt.subplots(figsize=(8, 5.2))
    h = 0.26
    for k, (j, lbl, col) in enumerate(judges):
        ys = [i + (k - 1) * h for i in range(len(rows))]
        ax.barh(ys, [100 * exp1[s][j]["rate"] for s in rows], height=h - 0.04, color=col, label=lbl)
    ax.set_yticks(range(len(rows)), [f"{LABEL[s]} (n={exp1[s]['runs']})" for s in rows])
    ax.invert_yaxis()
    ax.set_xlim(0, 100)
    ax.set_xlabel("canaries stopped within 6 minutes (%)")
    ax.set_title("exp1: injected regressions caught", loc="left")
    ax.grid(axis="y", visible=False)
    ax.legend(loc="lower right")
    save(fig, "exp1_detection.png")


def false_alarms():
    exp2 = load("exp2.jsonl")
    if not exp2:
        return
    r = exp2[0]
    judges = [("sequential", "sequential, every 10 s"), ("fixed_mw", "Mann-Whitney, once"),
              ("kayenta_style", "Kayenta-style, once"), ("peeking_mw", "Mann-Whitney, every 10 s"),
              ("peeking_kayenta", "Kayenta-style, every 10 s"), ("static_tuned", "static limits (tuned)"),
              ("static_flagger", "static limits (Flagger defaults)")]
    fig, ax = plt.subplots(figsize=(8, 4.2))
    for i, (j, lbl) in enumerate(judges):
        rate, (lo, hi) = 100 * r[j]["rate"], r[j]["rate_ci95"]
        col = BLUE if j == "sequential" else (ORANGE if j.startswith("peeking") else GRAY)
        ax.barh(i, rate, height=0.6, color=col)
        ax.plot([100 * lo, 100 * hi], [i, i], color=INK2, linewidth=1.5)
        ax.text(max(rate, 100 * hi) + 1, i, f"{r[j]['failed']} of {r['runs']}", va="center", color=INK2, fontsize=9)
    ax.axvline(5, color=INK, linestyle="--", linewidth=1)
    ax.text(5.5, len(judges) - 0.4, "5% target", color=INK, fontsize=9)
    ax.set_yticks(range(len(judges)), [lbl for _, lbl in judges])
    ax.invert_yaxis()
    ax.set_xlabel("false alarms on A/A canaries (%), with 95% interval")
    ax.set_title(f"exp2: false alarms over {r['runs']} healthy canaries", loc="left")
    ax.grid(axis="y", visible=False)
    save(fig, "exp2_false_alarms.png")


def time_to_detect():
    j = load("judgements.jsonl")
    rows = [s for s in ORDER if any(x["scenario"] == s and x["sequential"]["failed"] for x in j)]
    if not rows:
        return
    fig, ax = plt.subplots(figsize=(8, 4.8))
    for i, s in enumerate(rows):
        mins = [x["sequential"]["minutes_to_fail"] for x in j if x["scenario"] == s and x["sequential"]["failed"]]
        ax.scatter(mins, [i] * len(mins), color=BLUE, alpha=0.45, s=28, linewidths=0)
        ax.scatter([statistics.median(mins)], [i], color=BLUE, s=90, marker="|", linewidths=2.5)
    ax.axvline(6, color=ORANGE, linewidth=2)
    ax.text(5.9, -0.7, "fixed horizon: 6 min", color=INK, ha="right", fontsize=9)
    ax.set_yticks(range(len(rows)), [LABEL[s] for s in rows])
    ax.invert_yaxis()
    ax.set_xlim(0, 6.5)
    ax.set_xlabel("minutes until the sequential judge stopped the canary (dots: runs, bar: median)")
    ax.set_title("exp3: time to stop a bad canary", loc="left")
    ax.grid(axis="y", visible=False)
    save(fig, "exp3_time_to_detect.png")


def simulation():
    sim = load("sim_aa.jsonl")
    if not sim:
        return
    phis = sorted({r["phi"] for r in sim})
    methods = [("peeking", "Mann-Whitney re-checked (peeking)", ORANGE), ("sequential", "sequential t-test", BLUE),
               ("sequential_batched", "sequential, 3-interval batches", AQUA), ("fixed", "one test at the horizon", GRAY)]
    fig, axes = plt.subplots(1, len(phis), figsize=(10, 3.8), sharey=True)
    for ax, phi in zip(axes, phis):
        for m, lbl, col in methods:
            pts = sorted((r["looks"], 100 * r["rate"]) for r in sim if r["phi"] == phi and r["method"] == m)
            if pts:
                ax.plot([p[0] for p in pts], [p[1] for p in pts], color=col, linewidth=2, marker="o", markersize=4, label=lbl)
        ax.axhline(5, color=INK, linestyle="--", linewidth=1)
        ax.set_xscale("log")
        ax.set_xticks([6, 12, 36, 144], ["6", "12", "36", "144"])
        ax.set_title(f"autocorrelation {phi}", loc="left", fontsize=10)
        ax.set_xlabel("checks (10 s intervals)")
    axes[0].set_ylabel("false alarms on A/A (%)")
    axes[0].legend(loc="upper left", fontsize=8)
    fig.suptitle("Peeking versus an always-valid test (synthetic A/A, 5% target)", x=0.01, ha="left", fontweight="bold", color=INK)
    save(fig, "sim_peeking.png")


def rollouts():
    exp5 = [r for r in load("exp5.jsonl")]
    if not exp5:
        return
    scen = [s for s in ["aa"] + ORDER if any(r["scenario"] == s for r in exp5)]
    fig, ax = plt.subplots(figsize=(8, 4))
    for k, (mode, col) in enumerate([("sequential", BLUE), ("fixed", ORANGE)]):
        for i, s in enumerate(scen):
            vals = [100 * r["canary_user_share"] for r in exp5 if r["scenario"] == s and r["mode"] == mode]
            if vals:
                ax.scatter(vals, [i + (k - 0.5) * 0.3] * len(vals), color=col, s=36, alpha=0.8, linewidths=0,
                           label=f"{mode} judge" if i == 0 else None)
    ax.set_yticks(range(len(scen)), [LABEL[s] for s in scen])
    ax.invert_yaxis()
    ax.set_xlabel("users who reached the canary before rollback or promotion (%)")
    ax.set_title("exp5: exposure during live rollouts (1% to 5% to 25%)", loc="left")
    ax.grid(axis="y", visible=False)
    ax.legend(loc="lower right")
    save(fig, "exp5_exposure.png")


if __name__ == "__main__":
    os.makedirs(D, exist_ok=True)
    detection()
    false_alarms()
    time_to_detect()
    simulation()
    rollouts()
