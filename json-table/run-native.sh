#!/usr/bin/env bash
# Runs the GraalVM native executable. Build it first with:  mvn -Pnative package
# Usage: ./run-native.sh sample.json     or     cat sample.json | ./run-native.sh -
# On macOS install coreutils and replace "date" with "gdate" (the stock date has no %N).
cd "$(dirname "$0")" || exit 1

start=$(date +%s%3N)
echo "before: $(date '+%F %T.%3N')" >&2

./target/json-table "$@"
status=$?

end=$(date +%s%3N)
echo "after:  $(date '+%F %T.%3N')" >&2
echo "elapsed: $((end - start)) ms (native)" >&2
exit $status
