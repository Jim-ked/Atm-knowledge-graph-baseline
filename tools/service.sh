#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -f "$PROJECT_ROOT/tools/env.sh" ]; then . "$PROJECT_ROOT/tools/env.sh"; fi
if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
    MAVEN_COMMAND="$MAVEN_HOME/bin/mvn"
elif [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/mvn" ]; then
    MAVEN_COMMAND="$MAVEN_HOME/mvn"
else
    MAVEN_COMMAND=mvn
fi

if ! command -v "$MAVEN_COMMAND" >/dev/null 2>&1 && [ ! -x "$MAVEN_COMMAND" ]; then
  echo "ERROR: Maven was not found. Put mvn on PATH or set MAVEN_HOME." >&2
  exit 2
fi

cd "$PROJECT_ROOT"
exec "$MAVEN_COMMAND" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=org.atmkg.tools.KgServiceMain
