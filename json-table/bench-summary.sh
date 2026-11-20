#!/usr/bin/env bash
# Writes the Markdown report of one benchmark job to standard output.
# The workflow appends it to $GITHUB_STEP_SUMMARY; locally:  ROWS=10 ./bench-summary.sh > summary.md
# Expects in the current folder: bench.json (from hyperfine), versions.txt, machine.txt, sample.json, target/json-table.
cd "$(dirname "$0")" || exit 1
rows=${ROWS:-?}
src=${SRC_URL:-.}

details() {   # details TITLE LANGUAGE FILE
  echo "<details><summary><code>$1</code></summary>"
  echo
  echo "\`\`\`$2"
  cat "$3"
  echo "\`\`\`"
  echo "</details>"
  echo
}

cat <<MD
## json-table: who turns JSON into a table fastest? ($rows rows, $(cat machine.txt 2>/dev/null | head -1))

Every candidate is the same small program: read a JSON array of objects from a file and print it as a text table.
The time is for the **whole process**: starting the runtime, reading and parsing \`data.json\` ($rows rows, $(wc -c < data.json | tr -d ' ') bytes),
building the table and writing it to a discarded output. [hyperfine](https://github.com/sharkdp/hyperfine) ran each command
several times after warm-up runs. The table is sorted, fastest first; "Relative" is the mean divided by the fastest mean.

MD
# The table is built from hyperfine's JSON, because its own Markdown layout differs between versions.
if [ -s bench.json ]; then
  # hyperfine 1.x: .mean at the top of each result; 2.x (JSON schema 2): .summary.time_wall_clock.mean
  jq -r '
    def stats: if has("mean") then . else .summary.time_wall_clock end;
    def human: if . >= 1 then (. * 100 | round / 100 | tostring) + " s" elif . >= 0.01 then (. * 10000 | round / 10 | tostring) + " ms" else (. * 100000 | round / 100 | tostring) + " ms" end;
    .results |= map(. as $r | stats + {command: ($r.command // $r.name)})
    | (.results | map(.mean) | min) as $best
    | "| Candidate | Mean | ± | Min | Max | Relative |",
      "| :--- | ---: | ---: | ---: | ---: | ---: |",
      (.results | sort_by(.mean)[]
        | "| \(.command) | **\(.mean | human)** | \(.stddev | human) | \(.min | human) | \(.max | human) | \(.mean / $best * 100 | round / 100) |")
  ' bench.json
else
  echo "_no results: the benchmark step did not finish_"
fi
cat <<MD

> All candidates of this job ran on the same machine, one after another, so the rows of this table are comparable.
> Do not compare absolute numbers between jobs: each job gets a different machine.
> Before timing, the job checked that every candidate prints byte-for-byte the same table.

---

### Candidates

| Candidate | Source | How it is started |
| --- | --- | --- |
| Corretto, Oracle JDK, Zulu, GraalVM (JIT) | [Main.java]($src/src/main/java/org/example/jsontable/Main.java), [Item.java]($src/src/main/java/org/example/jsontable/Item.java) (Jackson 3 + Picocli) | \`java -jar json-table-1.0.jar data.json\`, same jar on each JVM |
| GraalVM (native) | the same Java sources | executable built by \`native-image\` from the same classes |
| jq + bash | [json-table-jq.sh]($src/json-table-jq.sh) | bash script that calls \`jq\` once |
| Go | [alt/go/main.go]($src/alt/go/main.go) | compiled executable, standard library only |
| Node.js | [alt/json-table.mjs]($src/alt/json-table.mjs) | \`node json-table.mjs data.json\`, built-ins only |
| Python | [alt/json_table.py]($src/alt/json_table.py) | \`python3 json_table.py data.json\`, standard library only |

---

### What goes in and what comes out

The benchmark input is generated ($rows objects of the same shape). To show what the program does, here is the small hand-written sample:

MD
details "sample.json (input)" json sample.json
target/json-table sample.json 2>/dev/null > sample-output.txt
details "json-table sample.json (output)" text sample-output.txt
echo "The first rows of the generated benchmark input:"
echo
jq -c '.[:3][]' data.json > data-head.txt
details "data.json (first 3 of $rows objects, one per line)" json data-head.txt
echo "---"
echo
echo "### Environment"
echo
details "machine" text machine.txt
details "versions" text versions.txt
