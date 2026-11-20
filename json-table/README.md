# json-table

A small command-line tool that turns a JSON array of objects into a table, written to see how fast
a Java program (Jackson 3 + Picocli) starts on an ordinary JVM, as a GraalVM native executable,
and next to the same program in jq, Go, Node.js and Python.

![json-table --date-only sample.json](docs/example.svg)

The input of that run is [`sample.json`](sample.json):

```json
[
  {
    "id": "T-1",
    "name": "Fix the login page",
    "createdAt": "2026-11-02T09:30:00Z",
    "assignee": "anna",
    "labels": ["bug", "ui"],
    "estimate": { "days": 2, "risk": "low" }
  },
  {
    "id": "T-2",
    "name": "Write release notes",
    "createdAt": "2026-11-09T14:05:00Z",
    "assignee": "boris",
    "done": true
  },
  {
    "id": "T-3",
    "name": "Upgrade to Java 25",
    "createdAt": "2026-11-16T08:00:00Z",
    "assignee": null,
    "labels": ["build"],
    "estimate": { "days": 5, "risk": "high" }
  }
]
```

- `id`, `name` and `createdAt` are the properties the program knows; they come first.
- Every other property gets its own column, in order of first appearance, with its name in quotes and in yellow.
- Nested objects and arrays are printed as pretty JSON inside the cell.
- A missing property and `null` both give an empty cell.

## Build and run

```
mvn package                 # target/json-table-1.0.jar, runs on any JDK 21+
mvn -Pnative package        # also target/json-table, needs GraalVM as JAVA_HOME

java -jar target/json-table-1.0.jar sample.json
target/json-table --date-only sample.json
cat sample.json | target/json-table -
```

Without arguments, or with `--help`, it prints what it expects:

```text
Usage: json-table [-hV] [--date-only] [--debug] [--timing] [--color=WHEN] FILE

Prints a JSON array of objects as a table.

Expected input
  A JSON array whose elements are objects, for example:

    [ { "id": "T-1", "name": "Fix the login page", "assignee": "anna" } ]

  id, name        text, each in its own column
  createdAt       ISO-8601 instant, in its own column
  anything else   one extra column per property; nested objects
                  and arrays are shown as pretty JSON

Parameters
      FILE           JSON file to read; use - for standard input.

Options
      --date-only    Show createdAt as a date (UTC), without the time.
      --color=WHEN   auto (default: colours only on a terminal and without
                       NO_COLOR), always or never.
      --timing       Print the start time, the end time and the duration to
                       standard error.
      --debug        On error, print the full stack trace instead of a one-line
                       message.
  -h, --help         Show this help message and exit.
  -V, --version      Print version information and exit.

Examples
  json-table sample.json
  json-table --date-only sample.json
  cat sample.json | json-table -

Exit codes
  0  success
  1  the input could not be read or is not valid
  2  wrong command-line arguments
```

## Errors

Nothing escapes as a raw stack trace. An error is one line that starts with a label, in bold red on a terminal;
`--debug` adds the stack trace.

| Label | When |
| :--- | :--- |
| `FILE NOT FOUND:` | the file does not exist |
| `ACCESS DENIED:` | the file cannot be read |
| `INVALID JSON:` | the text is not JSON; the message has the line and column |
| `INVALID DATA:` | valid JSON that does not fit, for example `createdAt` is not an instant |
| `I/O ERROR:` | any other failure while reading |
| `USAGE ERROR:` | unknown option, wrong option value or missing `FILE` (exit code 2) |
| `ERROR:` | anything else |

[`smoke-test.sh`](smoke-test.sh) checks the help, the exit codes and these messages for both builds;
the [`json-table`](../.github/workflows/json-table.yml) workflow runs it.

## The same program in other languages

Each one prints byte-for-byte the same table as the Java program run without options (no colours, no `--date-only`).

| Language | Source | Notes |
| :--- | :--- | :--- |
| jq + bash | [`json-table-jq.sh`](json-table-jq.sh) | one `jq` call; the table layout is written in the jq language |
| Go | [`alt/go/main.go`](alt/go/main.go) | standard library only; reads tokens to keep the property order |
| Node.js | [`alt/json-table.mjs`](alt/json-table.mjs) | built-ins only |
| Python | [`alt/json_table.py`](alt/json_table.py) | standard library only |

They print `createdAt` as it is written in the input, while Java parses it into an `Instant`; for inputs like the ones here the result is the same.

## Benchmark

The [`json-table-bench`](../.github/workflows/json-table-bench.yml) workflow times all nine candidates:
the same jar on Corretto 25, Oracle JDK 25, Zulu 25 and GraalVM 25, the GraalVM native executable,
the jq script, Go, Node.js 24 and Python 3.14.

### Where the results are

**[Latest benchmark report](https://github.com/krm-demo/ai-misc-graalvm/releases/latest)**, published as a GitHub Release.
[All reports](https://github.com/krm-demo/ai-misc-graalvm/releases): the 5 newest are kept.

Each report has the result tables for every platform and input size, how many times the native
executable beats the JVM, the sources at that commit, the sample input and output, the machine and
the versions of every runtime. The raw `hyperfine` results are attached as `bench-results.zip`.

The same report is also in the summary of the workflow run, but GitHub deletes workflow runs, their
summaries and artifacts after 90 days, so the release is the copy that stays.

### How to run it

- **By hand:** Actions, [`json-table-bench`](https://github.com/krm-demo/ai-misc-graalvm/actions/workflows/json-table-bench.yml), *Run workflow*.
- **Automatically:** on every push to `main` that changes code under `json-table/` or the workflow itself;
  changes to Markdown files and `docs/` do not start it.

A run takes about 10 minutes.

### How it works

```text
json-table-bench
├── bench  (matrix: 3 platforms x 3 input sizes = 9 jobs in parallel, each on its own machine)
│   ├── install 4 JDKs, GraalVM, Go, Node.js, Python, hyperfine
│   ├── build the jar, the native executable and the Go executable
│   ├── generate data.json (10, 1,000 or 100,000 objects)
│   ├── check that all candidates print byte-for-byte the same table
│   ├── time every candidate with hyperfine (whole process: start, read, parse, print)
│   └── write this job's report to the run summary, upload bench.json and friends
└── report  (after all bench jobs, even if some failed)
    ├── download the results of every job
    ├── build one combined report (bench-report.py)
    ├── write it to the run summary and publish it as release json-table-bench-<run number>
    └── delete benchmark releases older than the 5 newest
```

Numbers are comparable only inside one table column: each job gets a different machine.
Shared CI machines are noisy, the macOS ones most of all.

### What we saw (November 2026)

- Go was the fastest at every input size and on every platform.
- Corretto, Oracle JDK and Zulu could not be told apart.
- On small inputs the native executable started about 50 times faster than the same jar on a JVM
  (somewhat less on macOS), on a par with jq.
- On 100,000 objects the native executable and the JVMs were close, and Node.js was about twice as fast as both.
- jq fell far behind on large inputs, because the table layout is written in the jq language.

## Which technology for which job

This is a judgement from writing the same program six times, not a measurement.
Effort: **low** = an afternoon for someone new to the tool, **high** = needs extra libraries, build set-up or care.

| | Java on a JVM | Java native image | Go | Node.js | Python | jq + bash |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Lines of code for json-table (without blank lines and comments) | ~300, with a full CLI and error handling | same code | ~220 | ~55 | ~65 | ~70 |
| Libraries used here | Jackson, Picocli, Lombok | same | none | none | none | jq |
| Build | `mvn package`, seconds | `native-image`, 1-2 min, needs GraalVM | `go build`, seconds | none | none | none |
| What you ship | jar + a JRE | one executable per OS and CPU | one executable per OS and CPU, easy cross-compiling | script + Node.js | script + Python | script + jq |
| Start-up (Linux, 10 rows) | 0.4-0.5 s | ~10 ms | ~2 ms | ~25 ms | ~20 ms | ~10 ms |
| Large input (Linux, 100,000 rows) | ~3 s | ~3 s | ~0.6 s | ~1.2 s | ~2.5 s | ~13 s |
| Effort for this simple task | medium: types, build file | medium-high: plus reflection metadata and native tests | medium: `encoding/json` loses the property order, so we wrote a token parser | **low** | **low** | low for queries, high for layout |
| Typed models, validation, polymorphism | **low**: records + Jackson annotations | medium: every type registered | medium: struct tags, no unions | medium: needs TypeScript and a schema library | medium: dataclasses or pydantic | high |
| Streaming files larger than memory | **low**: `MappingIterator`, used here | low | low: `json.Decoder` tokens | medium: needs a library | medium: needs a library (`ijson`) | high: `--stream` |
| Long-running service | **best fit**: the JIT gets faster with time | fast start, lower peak speed | good | good | fair | not suitable |
| Readability of our version | good, but the longest | same | most verbose | most compact | most compact | compact but cryptic |

In short:

- **One-off scripts and quick reports:** Python or Node.js. Least code, nothing to build.
- **A command-line tool to hand to other people:** Go, or a Java native image if the team and the code are Java already.
- **Complex data models in a JVM codebase:** Java with Jackson. Use the JVM for services; build a native image for CLIs,
  serverless functions and anything that starts often. See [Jackson in a GraalVM native image](../docs/jackson-native-image.md).
- **jq:** for querying and reshaping JSON in a shell pipeline, not for formatting output.

## Files

| Path | What |
| :--- | :--- |
| `src/main/java/org/example/jsontable/` | `Main` (command line, table) and `Item` (one array element) |
| `src/main/resources/META-INF/native-image/` | reflection configuration for `Item`; Picocli's is generated at build time |
| `sample.json` | the example above |
| `run-jvm.sh`, `run-native.sh` | run one build and print the elapsed time |
| `smoke-test.sh` | command-line checks |
| `json-table-jq.sh`, `alt/` | the other implementations |
| `bench-summary.sh` | the report each benchmark job writes to its run summary |
| `bench-report.py` | the combined report that is published as a release |
| `docs/example.svg`, `docs/ansi2svg.py` | the picture above and the script that draws it from `--color=always` output |
