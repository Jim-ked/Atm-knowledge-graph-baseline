#!/bin/sh

PROJECT_ROOT=$(CDPATH= cd "$(dirname "$0")/.." && pwd) || exit 1
if [ -f "$PROJECT_ROOT/tools/env.sh" ]; then . "$PROJECT_ROOT/tools/env.sh"; fi
MAVEN_COMMAND=mvn
JAVA_COMMAND=java

if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then MAVEN_COMMAND="$MAVEN_HOME/bin/mvn";
elif [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/mvn" ]; then MAVEN_COMMAND="$MAVEN_HOME/mvn"; fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA_COMMAND="$JAVA_HOME/bin/java";
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/java" ]; then JAVA_COMMAND="$JAVA_HOME/java"; fi

if [ -f "$PROJECT_ROOT/target/atm-knowledge-graph-1.0-SNAPSHOT.jar" ] && [ -n "$(find "$PROJECT_ROOT/target/lib" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
    cd "$PROJECT_ROOT" || exit 1
    exec "$JAVA_COMMAND" -cp "target/atm-knowledge-graph-1.0-SNAPSHOT.jar:target/lib/*" \
        org.atmkg.tools.review.ReviewTool "$PROJECT_ROOT"
fi

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
