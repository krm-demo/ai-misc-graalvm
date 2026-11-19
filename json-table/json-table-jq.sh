#!/usr/bin/env bash
# The same table as the Java json-table, made with jq only (no colours).
# Usage: ./json-table-jq.sh FILE     or     cat FILE | ./json-table-jq.sh -
set -o pipefail

usage() {
  cat <<'HELP'
Usage: json-table-jq.sh [--debug] FILE

Prints a JSON array of objects as a table.

Expected input: a JSON array whose elements are objects, for example
  [ { "id": "p-1", "name": "Monthly sweep", "owner": "treasury" } ]

  id, name       text, each in its own column
  createdAt      ISO-8601 instant, in its own column (printed as it is written)
  anything else  one extra column per property; nested values as pretty JSON

  FILE      JSON file to read; use - for standard input
  --debug   on error, show jq's own message
  --help    show this help
HELP
}

debug=false
file=
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --debug)   debug=true ;;
    -)         file=- ;;
    -*)        echo "USAGE ERROR: unknown option: $arg" >&2; exit 2 ;;
    *)         file=$arg ;;
  esac
done
if [ $# -eq 0 ]; then usage; exit 0; fi
if [ -z "$file" ]; then echo "USAGE ERROR: missing FILE" >&2; exit 2; fi
if [ "$file" != - ] && [ ! -e "$file" ]; then echo "FILE NOT FOUND: $file" >&2; exit 1; fi
if [ "$file" != - ] && [ ! -r "$file" ]; then echo "ACCESS DENIED: $file" >&2; exit 1; fi

read -r -d '' program <<'JQ'
# Pretty JSON in the layout of Jackson's default pretty printer.
def pretty($indent):
  if type == "object" then
    if length == 0 then "{ }"
    else "{\n"
      + ([to_entries[] | $indent + "  " + (.key | tojson) + " : " + (.value | pretty($indent + "  "))] | join(",\n"))
      + "\n" + $indent + "}"
    end
  elif type == "array" then
    if length == 0 then "[ ]" else "[ " + ([.[] | pretty($indent)] | join(", ")) + " ]" end
  else tojson
  end;
def text: if . == null then "" elif type == "string" then . else tojson end;
def cell: if type == "object" or type == "array" then pretty("") else text end;
def pad($width): . + (" " * ($width - length) // "");

if type != "array" then error("the input is not a JSON array") else . end
| map(if type != "object" then error("an element of the array is not an object") else . end)
| . as $items
| ["id", "name", "createdAt"] as $known
| (reduce ($items[] | keys_unsorted[]) as $key ([];
    if ($known | index($key)) != null or index($key) != null then . else . + [$key] end)) as $extra
| ($known + ($extra | map(tojson))) as $headers
| [$items[] | . as $item
    | [($known[] | $item[.] | text), ($extra[] | $item[.] | cell)] | map(split("\n"))] as $rows
| [range($headers | length) as $c
    | [($headers[$c] | length), ($rows[] | .[$c][] | length)] | max] as $widths
| ($widths | map("─" * .) | join("─┼─")) as $rule
| ([range($headers | length) as $c | $headers[$c] | pad($widths[$c])] | join(" │ ")),
  $rule,
  ($rows[]
    | . as $row
    | (range([$row[] | length] | max) as $line
        | [range($row | length) as $c | ($row[$c][$line] // "") | pad($widths[$c])] | join(" │ ")),
      $rule)
JQ

if [ "$file" = - ]; then input=/dev/stdin; else input=$file; fi
if ! message=$(jq -r "$program" "$input" 2>&1); then
  if $debug; then echo "$message" >&2; fi
  echo "INVALID INPUT: $(printf '%s\n' "$message" | tail -n 1 | sed -E 's/^jq: error \([^)]*\): //; s/^jq: //')" >&2
  exit 1
fi
printf '%s\n' "$message"
