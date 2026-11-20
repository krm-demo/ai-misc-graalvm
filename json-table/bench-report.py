#!/usr/bin/env python3
"""Builds the combined Markdown report of one json-table-bench run.

Usage: bench-report.py RESULTS_DIR > report.md

RESULTS_DIR holds one folder per matrix job, named bench-<os>-<rows>, as downloaded by
actions/download-artifact. Each folder has bench.json (hyperfine), machine.txt, versions.txt,
data-size.txt and sample-output.txt. Missing folders or files show up as gaps, not errors.

The workflow passes run details through the environment:
RUN_NUMBER, RUN_URL, COMMIT_SHA, REPO_URL, RUN_DATE.
"""
import json
import os
import sys
from pathlib import Path

PLATFORMS = {
    "ubuntu-latest": "Linux x64",
    "ubuntu-24.04-arm": "Linux ARM64",
    "macos-latest": "macOS ARM64",
}
SIZES = [10, 1000, 100000]
NATIVE, JVM = "GraalVM 25 (native)", "Corretto 25"

env = os.environ.get
run_number = env("RUN_NUMBER", "?")
run_url = env("RUN_URL", "")
sha = env("COMMIT_SHA", "")
repo_url = env("REPO_URL", "https://github.com/krm-demo/ai-misc-graalvm")
run_date = env("RUN_DATE", "")
src = f"{repo_url}/blob/{sha or 'main'}/json-table"


def read(path):
    try:
        return Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def load(results_dir):
    """Returns {platform: {rows: {"results": {command: result}, "dir": Path}}}."""
    found = {}
    for platform in PLATFORMS:
        for rows in SIZES:
            folder = Path(results_dir) / f"bench-{platform}-{rows}"
            text = read(folder / "bench.json")
            if text:
                results = {r["command"]: r for r in json.loads(text)["results"]}
                found.setdefault(platform, {})[rows] = {"results": results, "dir": folder}
    return found


def human(seconds):
    if seconds is None:
        return "–"
    if seconds >= 1:
        return f"{seconds:.2f} s"
    ms = seconds * 1000
    return f"{ms:.0f} ms" if ms >= 100 else f"{ms:.1f} ms"


def platform_table(runs):
    commands = []
    for rows in SIZES:
        for command in runs.get(rows, {}).get("results", {}):
            if command not in commands:
                commands.append(command)

    def mean(command, rows):
        result = runs.get(rows, {}).get("results", {}).get(command)
        return result["mean"] if result else None

    sort_rows = 1000 if 1000 in runs else next(iter(runs))
    commands.sort(key=lambda c: mean(c, sort_rows) if mean(c, sort_rows) is not None else float("inf"))
    best = {rows: min((mean(c, rows) for c in commands if mean(c, rows) is not None), default=None) for rows in SIZES}

    lines = ["| Candidate | 10 rows | 1,000 rows | 100,000 rows |", "| :--- | ---: | ---: | ---: |"]
    for command in commands:
        cells = []
        for rows in SIZES:
            value = mean(command, rows)
            result = runs.get(rows, {}).get("results", {}).get(command)
            cell = "–" if value is None else f"{human(value)} ± {human(result['stddev'])}"
            if value is not None and value == best[rows]:
                cell = f"**{cell}**"
            cells.append(cell)
        lines.append(f"| {command} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def speedup_table(found):
    lines = ["| Platform | 10 rows | 1,000 rows | 100,000 rows |", "| :--- | ---: | ---: | ---: |"]
    for platform, runs in found.items():
        cells = []
        for rows in SIZES:
            results = runs.get(rows, {}).get("results", {})
            if NATIVE in results and JVM in results:
                cells.append(f"{results[JVM]['mean'] / results[NATIVE]['mean']:.1f}x")
            else:
                cells.append("–")
        lines.append(f"| {PLATFORMS[platform]} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def details(summary, language, text):
    return f"<details><summary>{summary}</summary>\n\n```{language}\n{text}\n```\n</details>\n"


def size_of(found, rows):
    for runs in found.values():
        size = read(runs.get(rows, {}).get("dir", Path("-")) / "data-size.txt")
        if size.isdigit():
            return int(size)
    return None


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip())
    found = load(sys.argv[1])
    if not found:
        sys.exit("no bench.json found: every benchmark job failed")

    jobs_done = sum(len(runs) for runs in found.values())
    sizes = ", ".join(
        f"{rows:,} rows ({size_of(found, rows) / 1e6:.1f} MB)" if size_of(found, rows) and size_of(found, rows) > 1e6
        else f"{rows:,} rows ({(size_of(found, rows) or 0) / 1e3:.1f} kB)"
        for rows in SIZES)
    out = []
    add = out.append

    add(f"# json-table benchmark №{run_number}")
    add("")
    add(f"Run [№{run_number}]({run_url}) on {run_date}, commit [`{sha[:7]}`]({repo_url}/commit/{sha}). "
        f"{jobs_done} of {len(PLATFORMS) * len(SIZES)} jobs finished.")
    add("")
    add("The same small program, *print a JSON array of objects as a text table*, written in Java (Jackson 3 + Picocli) "
        "and run on four JVMs and as a GraalVM native executable, next to the same program in jq, Go, Node.js and Python. "
        f"What the program does and how to run it: [json-table/README.md]({repo_url}/blob/main/json-table/README.md).")
    add("")
    add("## How it was measured")
    add("")
    add("- **What is timed:** the whole process, from start to exit: starting the runtime, reading and parsing the file, "
        "building the table and writing it to a discarded output.")
    add("- **Tool:** [hyperfine](https://github.com/sharkdp/hyperfine), 2 warm-up runs, then 15 timed runs "
        "(7 for the largest input). The tables show the mean ± standard deviation.")
    add(f"- **Matrix:** {len(PLATFORMS)} platforms x {len(SIZES)} input sizes = {len(PLATFORMS) * len(SIZES)} parallel jobs. "
        f"Input sizes: {sizes}.")
    add("- **Fairness:** all candidates of one job run one after another on the same machine, and the job first checks "
        "that every candidate prints byte-for-byte the same table. Each job gets its own machine, so compare numbers "
        "only inside one column of one table.")
    add("- **Report:** a final job collected the results of all jobs into this text and published it as a release; "
        "the 5 newest benchmark releases are kept.")
    add("")
    add("## Results")
    add("")
    add("Rows are sorted by the 1,000-row column; the fastest mean of each column is in bold.")
    add("")
    for platform, label in PLATFORMS.items():
        if platform not in found:
            add(f"### {label} (`{platform}`)\n\n_No results: the jobs on this platform failed._\n")
            continue
        machine = read(next(iter(found[platform].values()))["dir"] / "machine.txt").splitlines()
        cpu = next((line.split(":", 1)[1].strip() for line in machine if line.startswith("Model name")), "")
        if not cpu and len(machine) > 1:
            cpu = machine[1].strip()
        add(f"### {label} (`{platform}`{', ' + cpu if cpu else ''})")
        add("")
        add(platform_table(found[platform]))
        add("")
    add("### Native executable vs. the same jar on a JVM")
    add("")
    add(f"How many times faster `{NATIVE}` is than `{JVM}` (mean time of the JVM divided by mean time of the native executable).")
    add("")
    add(speedup_table(found))
    add("")
    add("## Candidates")
    add("")
    add("| Candidate | Source | How it is started |")
    add("| :--- | :--- | :--- |")
    add(f"| Corretto 25, Oracle JDK 25, Zulu 25, GraalVM 25 (JIT) | [Main.java]({src}/src/main/java/org/example/jsontable/Main.java), "
        f"[Item.java]({src}/src/main/java/org/example/jsontable/Item.java) | `java -jar json-table-1.0.jar data.json`, the same jar on each JVM |")
    add(f"| GraalVM 25 (native) | the same Java sources | executable built by `native-image` |")
    add(f"| jq + bash | [json-table-jq.sh]({src}/json-table-jq.sh) | a bash script that calls `jq` once |")
    add(f"| Go | [alt/go/main.go]({src}/alt/go/main.go) | compiled executable, standard library only |")
    add(f"| Node.js 24 | [alt/json-table.mjs]({src}/alt/json-table.mjs) | `node json-table.mjs data.json`, built-ins only |")
    add(f"| Python 3.14 | [alt/json_table.py]({src}/alt/json_table.py) | `python3 json_table.py data.json`, standard library only |")
    add("")
    add("## What goes in and what comes out")
    add("")
    add("The benchmark input is generated: N objects of the same shape, for example")
    add("")
    add('```json\n{"id":"p-0","name":"Plan number 0","createdAt":"2026-11-01T09:30:00Z","owner":"team-0",'
        '"limits":{"min":0,"max":0},"tags":["weekly","auto"]}\n```')
    add("")
    add("To show what the program does, here is the hand-written sample and the table printed for it:")
    add("")
    add(details("<code>sample.json</code> (input)", "json", read(Path(__file__).with_name("sample.json"))))
    sample_output = ""
    for runs in found.values():
        for job in runs.values():
            sample_output = sample_output or read(job["dir"] / "sample-output.txt")
    add(details("<code>json-table sample.json</code> (output)", "text", sample_output or "(not available)"))
    add("## Environment")
    add("")
    for platform, runs in found.items():
        job = next(iter(runs.values()))["dir"]
        add(details(f"{PLATFORMS[platform]} (<code>{platform}</code>)", "text",
                    read(job / "machine.txt") + "\n\n" + read(job / "versions.txt")))
    add("## Caveats")
    add("")
    add("- Shared CI machines are noisy; macOS runners vary the most. Read small differences as equal.")
    add("- The Java program has not been profiled or tuned.")
    add("- jq is slow on large inputs because the table layout is written in the jq language; this says little about its parser.")
    add("")
    add(f"Raw hyperfine results of every job are attached to this release as `bench-results.zip`. "
        f"To repeat the measurement: Actions, `json-table-bench`, *Run workflow* ({repo_url}/actions/workflows/json-table-bench.yml).")
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
