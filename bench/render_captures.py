"""Renders text captures of `kubectl argo rollouts get rollout` into PNG frames for the README.

Usage: python bench/render_captures.py docs/argo
Writes docs/argo/<update>.png with the captures of one update side by side in time order.
"""
import glob
import os
import sys
import textwrap

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def frames(d, update):
    files = sorted(f for f in glob.glob(os.path.join(d, f"{update}-*.txt")) if not f.endswith("final.txt"))
    return files[:1] + [os.path.join(d, f"{update}-final.txt")]


def render(d, update, title):
    fs = [f for f in frames(d, update) if os.path.exists(f)]
    fig, axes = plt.subplots(1, len(fs), figsize=(9 * len(fs), 9), facecolor="#1a1a19")
    if len(fs) == 1:
        axes = [axes]
    for ax, f in zip(axes, fs):
        lines = open(f, encoding="utf-8").read().splitlines()
        text = "\n".join(w for line in lines for w in (textwrap.wrap(line, 96, subsequent_indent="                 ") or [""]))
        ax.set_facecolor("#1a1a19")
        ax.axis("off")
        label = os.path.basename(f).replace(update + "-", "").replace(".txt", "").split("-")[-1]
        ax.text(0, 1.02, "after " + label if label != "final" else "final state", color="#ffffff",
                fontsize=12, fontweight="bold", transform=ax.transAxes, family="DejaVu Sans")
        ax.text(0, 1, text, color="#e6e5e1", fontsize=7.2, family="DejaVu Sans Mono", va="top", transform=ax.transAxes)
    fig.suptitle(title, color="#ffffff", fontsize=14, fontweight="bold", x=0.01, ha="left")
    fig.savefig(os.path.join(d, f"{update}.png"), dpi=110, facecolor="#1a1a19", bbox_inches="tight")
    print("wrote", os.path.join(d, f"{update}.png"))


if __name__ == "__main__":
    d = sys.argv[1] if len(sys.argv) > 1 else "docs/argo"
    render(d, "healthy", "Argo Rollouts: healthy update, CanaryJudge analysis passes, rollout promoted")
    render(d, "regressed", "Argo Rollouts: update with +50% latency, CanaryJudge analysis fails, rollout aborted")
