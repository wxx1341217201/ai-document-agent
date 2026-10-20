"""Export figures from a completed, immutable M19E summary and raw observations.

Usage: python benchmarks/rabbitmq/plot_results.py <report-directory>
Requires matplotlib; no benchmark processes or external services are started.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

COLORS = {25: "#0072B2", 50: "#D55E00", 100: "#009E73"}


def case_id(config: dict) -> str:
    return f"{config['mode']}-b{config['batchSize']}-c{config['consumers']}"


def save(fig, folder: Path, name: str) -> None:
    for extension in ("png", "svg"):
        fig.savefig(folder / f"{name}.{extension}", dpi=180, facecolor="white", bbox_inches="tight")
    plt.close(fig)


def overview(summary: dict, folder: Path) -> None:
    rows = {case_id(row["config"]): row for row in summary["rows"]}
    fig, axes = plt.subplots(2, 2, figsize=(13.8, 8.8), constrained_layout=True)
    positions = list(range(5))
    configs = [("sync", 1), ("async", 1), ("async", 2), ("async", 4), ("async", 8)]
    metrics = [(axes[0, 0], "submitAckLatencyMs", "Server submission ACK", "Latency (ms; logarithmic)"),
               (axes[1, 0], "queueWaitLatencyMs", "Logical batch queue wait", "Latency (ms; logarithmic)"),
               (axes[1, 1], "endToEndLatencyMs", "Document submission to READY", "Latency (ms; logarithmic)")]
    for batch, color in COLORS.items():
        cases = [rows[f"{mode}-b{batch}-c{consumers}"]["evaluation"] for mode, consumers in configs]
        for ax, metric, title, ylabel in metrics:
            ax.plot(positions, [case[metric]["mean"] for case in cases], color=color, marker="o", linewidth=2)
            ax.plot(positions, [case[metric]["p95"] for case in cases], color=color, marker="x", linestyle="--", linewidth=1.2)
            ax.set(yscale="log", title=title, ylabel=ylabel)
        axes[0, 1].plot(positions, [case["peakStableWindow"]["documentsPerSecond"] for case in cases],
                        color=color, marker="o", linewidth=2, label=f"batchSize={batch}")
    for ax in axes.flat:
        ax.set_xticks(positions, ["Sync", "Async 1", "Async 2", "Async 4", "Async 8"])
        ax.grid(axis="y", alpha=.2)
        ax.spines[["top", "right"]].set_visible(False)
    axes[0, 0].axhline(250, color="#777777", linewidth=1, linestyle=":")
    axes[0, 0].text(4.1, 250, "250 ms\nACK target", va="center", fontsize=8, color="#666666")
    axes[0, 1].set(title="Peak stable completed throughput", ylabel="Completed documents / second", ylim=(0, None))
    axes[0, 1].legend(frameon=False)
    handles = [Line2D([0], [0], color="#333333", marker="o", label="Mean"),
               Line2D([0], [0], color="#333333", linestyle="--", marker="x", label="P95 (not a confidence interval)")]
    axes[1, 1].legend(handles=handles, frameon=False, fontsize=8, loc="upper right")
    gain = summary["comparison"]["throughputGain"]
    fig.suptitle(f"M19E | Stable throughput gain: {gain:.2f}x\n"
                 "Same frozen 100-document corpus, 20 outstanding submissions, 60-second stable windows", fontsize=14)
    fig.supxlabel("Sync: one serial document lane; async labels: consumers per document/batch queue.\n"
                  "Controlled local embeddings + test index, real MySQL/RabbitMQ. P95 and mean use all measured submissions.", fontsize=9)
    save(fig, folder, "overview")


def resources(summary: dict, folder: Path) -> None:
    sync = next(row for row in summary["rows"] if row["config"]["mode"] == "sync" and row["config"]["batchSize"] == 50)
    async_row = max((row for row in summary["rows"] if row["config"]["mode"] == "async" and row["config"]["batchSize"] == 50),
                    key=lambda row: row["evaluation"]["peakStableWindow"]["documentsPerSecond"])
    fig, axes = plt.subplots(4, 2, figsize=(13.8, 12.4), sharex="col", constrained_layout=True)
    maxima = [0.0, 0.0, 512.0, 0.0]
    for column, row in enumerate((sync, async_row)):
        name = case_id(row["config"])
        raw = json.loads((folder / f"{name}.json").read_text(encoding="utf-8"))
        origin = raw["measurementStartedAt"]
        points = raw["samples"]
        times = [(p["at"] - origin) / 1000 for p in points]
        cutoff = (raw["admissionStoppedAt"] - origin) / 1000
        peak = row["evaluation"]["peakStableWindow"]
        start, end = (peak["from"] - origin) / 1000, (peak["to"] - origin) / 1000
        bin_times, rates = [], []
        bin_start = 0.0
        while bin_start + 15 <= cutoff:
            completions = sum(origin + bin_start * 1000 <= d["readyAt"] < origin + (bin_start + 15) * 1000
                              for d in raw["documents"])
            bin_times.append(bin_start + 7.5)
            rates.append(completions / 15)
            bin_start += 15
        axes[0, column].plot(bin_times, rates, color="#0072B2", marker="o")
        maxima[0] = max(maxima[0], max(rates, default=0))
        axes[0, column].set(title=name, ylabel="Documents / s\n(15s descriptive bins)", ylim=(0, None))
        cpu = [0.0]
        for before, after in zip(points, points[1:]):
            cpu.append(100 * (after["processCpuTimeNanos"] - before["processCpuTimeNanos"])
                       / ((after["at"] - before["at"]) * 1_000_000 * 4))
        axes[1, column].plot(times, cpu, color="#009E73")
        maxima[1] = max(maxima[1], max(cpu, default=0))
        axes[1, column].set(ylabel="JVM CPU (%)\n4-core normalization", ylim=(0, None))
        axes[2, column].plot(times, [p["heapUsedBytes"] / 1048576 for p in points], color="#D55E00")
        axes[2, column].set(ylabel="JVM heap used (MiB)\n(excludes native/RSS)", ylim=(0, 512))
        axes[3, column].plot(times, [p["batchBacklog"] for p in points], color="#0072B2", label="Logical batch backlog")
        broker_ready = [sum(q["ready"] for q in p["rabbitQueues"]) for p in points]
        broker_unacked = [sum(q["unacknowledged"] for q in p["rabbitQueues"]) for p in points]
        maxima[3] = max(maxima[3], max((p["batchBacklog"] for p in points), default=0),
                        max(broker_ready, default=0), max(broker_unacked, default=0))
        if column == 1:
            axes[3, column].step(times, broker_ready, color="#D55E00", where="post", label="RabbitMQ ready messages")
            axes[3, column].step(times, broker_unacked, color="#009E73", where="post", label="RabbitMQ unacked messages")
        axes[3, column].set(ylabel="Pending count\n(batches vs stage messages)", xlabel="Seconds from measurement start", ylim=(0, None))
        axes[3, column].legend(frameon=False, fontsize=8)
        for ax in axes[:, column]:
            ax.axvspan(start, end, color="#009E73", alpha=.07)
            if times[-1] > cutoff:
                ax.axvspan(cutoff, times[-1], color="#777777", alpha=.12)
            ax.grid(axis="y", alpha=.2)
            ax.spines[["top", "right"]].set_visible(False)
    for row_index, maximum in enumerate(maxima):
        upper = maximum if row_index == 2 else max(1.0, maximum * 1.12)
        for ax in axes[row_index]:
            ax.set_ylim(0, upper)
    fig.suptitle("M19E | batchSize=50: matched sync and best async configuration\n"
                 "Green area: selected stable 60s window. Gray area: drain excluded from throughput.", fontsize=13)
    fig.supxlabel("CPU and memory describe the application JVM. RabbitMQ Management counters refresh about every 5s.\n"
                  "Each logical batch has two AMQP stage messages. Matching rows use the same y-axis range.", fontsize=9)
    save(fig, folder, "resources")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_directory", type=Path)
    args = parser.parse_args()
    folder = args.report_directory.resolve()
    summary = json.loads((folder / "summary.json").read_text(encoding="utf-8"))
    if not summary["comparison"]["formalMatrixComplete"]:
        raise SystemExit("A complete and valid 15-row matrix with stable windows is required.")
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "svg.fonttype": "none"})
    overview(summary, folder)
    resources(summary, folder)
    print(f"Exported overview/resources PNG and SVG to {folder}")


if __name__ == "__main__":
    main()
