#!/bin/sh

PROJECT_ROOT=$(CDPATH= cd "$(dirname "$0")/.." && pwd) || exit 1
MAVEN_COMMAND=mvn
JAVA_COMMAND=java

if [ -n "${MAVEN_HOME:-}" ]; then MAVEN_COMMAND="$MAVEN_HOME/bin/mvn"; fi
if [ -n "${JAVA_HOME:-}" ]; then JAVA_COMMAND="$JAVA_HOME/bin/java"; fi

if ! command -v "$MAVEN_COMMAND" >/dev/null 2>&1; then
    echo "ERROR: Maven was not found. Put mvn on PATH or set MAVEN_HOME." >&2
    exit 2
fi
if ! command -v "$JAVA_COMMAND" >/dev/null 2>&1; then
    echo "ERROR: Java was not found. Put java on PATH or set JAVA_HOME." >&2
    exit 2
fi

cd "$PROJECT_ROOT" || exit 1
exec "$MAVEN_COMMAND" -q -DskipTests compile \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=org.atmkg.tools.review.ReviewTool
