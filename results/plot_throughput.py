"""
Line chart of ingest throughput over time (10-second buckets) for A1.

Reads  results/throughput.csv  (bucketStartSec,swipesPerSec) written by client-part2
Writes results/throughput.png

Usage:  python results/plot_throughput.py [--warmup-end SECONDS]
"""
import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt

here = Path(__file__).resolve().parent

parser = argparse.ArgumentParser()
parser.add_argument("--warmup-end", type=float, default=None,
                    help="seconds into the run when warmup finished (shades that region)")
parser.add_argument("--csv", default=str(here / "throughput.csv"))
parser.add_argument("--out", default=str(here / "throughput.png"))
args = parser.parse_args()

starts, rates = [], []
with open(args.csv, newline="") as f:
    for row in csv.DictReader(f):
        starts.append(int(row["bucketStartSec"]))
        rates.append(int(row["swipesPerSec"]))

fig, ax = plt.subplots(figsize=(9, 4.5))
ax.plot(starts, rates, marker="o", linewidth=2)

if args.warmup_end is not None:
    ax.axvspan(0, args.warmup_end, alpha=0.12)
    ax.text(args.warmup_end / 2, max(rates) * 0.95, "warmup\n(32 threads)",
            ha="center", va="top", fontsize=9)

for x, y in zip(starts, rates):
    ax.annotate(f"{y:,}", (x, y), textcoords="offset points", xytext=(0, 8),
                ha="center", fontsize=8)

ax.set_title("SkiTrak ingest throughput over time (10-second buckets)")
ax.set_xlabel("Start of 10-second bucket (seconds since the run began)")
ax.set_ylabel("Successful swipes per second")
ax.set_ylim(bottom=0, top=max(rates) * 1.15)
ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda v, _: f"{int(v):,}"))
ax.grid(True, alpha=0.3)
fig.tight_layout()
fig.savefig(args.out, dpi=150)
print(f"Saved {args.out}")
