#!/usr/bin/env bash
# Checks the command-line behaviour of json-table: help, exit codes and error messages.
# Usage: ./smoke-test.sh java -jar target/json-table-1.0.jar
#        ./smoke-test.sh target/json-table
cd "$(dirname "$0")" || exit 1
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failures=0

# check NAME EXPECTED_EXIT_CODE STREAM(out|err) PATTERN [absent] -- uses the last run
run() {
  "$@" >"$tmp/out" 2>"$tmp/err" </dev/null
  code=$?
}
check() {
  local name=$1 want=$2 stream=$3 pattern=$4 absent=${5:-}
  local ok=true
  [ "$code" -eq "$want" ] || ok=false
  if [ -n "$absent" ]; then
    grep -q -E -- "$pattern" "$tmp/$stream" && ok=false
  else
    grep -q -E -- "$pattern" "$tmp/$stream" || ok=false
  fi
  if $ok; then
    echo "ok   - $name"
  else
    echo "FAIL - $name (exit $code, wanted $want; /$pattern/ ${absent:+must be absent }in std$stream)"
    sed 's/^/       out| /' "$tmp/out"
    sed 's/^/       err| /' "$tmp/err"
    failures=$((failures + 1))
  fi
}

run "$@"
check "no arguments prints the help" 0 out '^Usage: json-table'
check "the help says what input is expected" 0 out 'JSON array whose elements are objects'
cp "$tmp/out" "$tmp/help-no-args"

run "$@" --help
check "--help prints the help" 0 out '^Usage: json-table'
if diff -q "$tmp/help-no-args" "$tmp/out" >/dev/null; then
  echo "ok   - no arguments and --help print the same text"
else
  echo "FAIL - no arguments and --help print different text"; failures=$((failures + 1))
fi
echo "----- help text -----"; cat "$tmp/out"; echo "---------------------"

run "$@" --version
check "--version" 0 out '^json-table 1\.0$'

run "$@" sample.json
check "a file argument prints the table" 0 out 'Monthly sweep'

"$@" - <sample.json >"$tmp/out" 2>"$tmp/err"; code=$?
check "'-' reads standard input" 0 out 'Monthly sweep'

run "$@" no-such-file.json
check "missing file: one-line message" 1 err '^FILE NOT FOUND: no-such-file\.json$'
check "missing file: no stack trace without --debug" 1 err 'NoSuchFileException' absent
echo "       err| $(grep -v -E '^(start|end):' "$tmp/err")"

run "$@" --debug no-such-file.json
check "missing file with --debug: message" 1 err '^FILE NOT FOUND: no-such-file\.json$'
check "missing file with --debug: stack trace" 1 err 'NoSuchFileException'
check "missing file with --debug: stack frames" 1 err 'at .*org\.example\.jsontable\.Main'

printf '[{"id": ' >"$tmp/broken.json"
run "$@" "$tmp/broken.json"
check "broken JSON" 1 err '^INVALID JSON: '
echo "       err| $(grep -v -E '^(start|end):' "$tmp/err")"

printf '[{"id": "p-1", "createdAt": "yesterday"}]' >"$tmp/bad-data.json"
run "$@" "$tmp/bad-data.json"
check "valid JSON with a wrong value" 1 err '^INVALID DATA: '
echo "       err| $(grep -v -E '^(start|end):' "$tmp/err")"

run "$@" --no-such-option sample.json
check "unknown option" 2 err '^USAGE ERROR: '
echo "       err| $(cat "$tmp/err")"

run "$@" --debug
check "missing FILE" 2 err '^USAGE ERROR: '

echo
if [ "$failures" -eq 0 ]; then echo "all checks passed"; else echo "$failures check(s) failed"; exit 1; fi
