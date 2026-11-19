#!/usr/bin/env bash
# Runs the ordinary JVM build. Build it first with:  mvn package
# Usage: ./run-jvm.sh sample.json     or     cat sample.json | ./run-jvm.sh -
# On macOS install coreutils and replace "date" with "gdate" (the stock date has no %N).
cd "$(dirname "$0")" || exit 1

start=$(date +%s%3N)
echo "before: $(date '+%F %T.%3N')" >&2

java -jar target/json-table-1.0.jar "$@"
status=$?

end=$(date +%s%3N)
echo "after:  $(date '+%F %T.%3N')" >&2
echo "elapsed: $((end - start)) ms (JVM)" >&2
exit $status
