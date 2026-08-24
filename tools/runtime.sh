#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
[ -f "$PROJECT_ROOT/tools/env.sh" ] && . "$PROJECT_ROOT/tools/env.sh"

JAR="$PROJECT_ROOT/target/atm-knowledge-graph-1.0-SNAPSHOT.jar"
LIB="$PROJECT_ROOT/target/lib"
PID_FILE="$PROJECT_ROOT/runtime/state/service.pid"
LOG_DIR="$PROJECT_ROOT/runtime/logs"
LOG_FILE="$LOG_DIR/service.log"
ATMKG_API_HOST="${ATMKG_API_HOST:-127.0.0.1}"
ATMKG_API_PORT="${ATMKG_API_PORT:-18080}"

resolve_java() {
    if [ -z "${JAVA_HOME:-}" ]; then
        echo "ERROR: JAVA_HOME is not set. Formal runtime requires the private JDK configured in tools/env.sh." >&2
        exit 2
    elif [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_COMMAND="$JAVA_HOME/bin/java"
    elif [ -x "$JAVA_HOME/java" ]; then
        JAVA_COMMAND="$JAVA_HOME/java"
    else
        echo "ERROR: Java executable not found under JAVA_HOME=$JAVA_HOME" >&2
        exit 2
    fi
}
[ -n "${JAVA_COMMAND:-}" ] || { echo "ERROR: Java 17 not found; set JAVA_HOME in tools/env.sh." >&2; exit 2; }

running_pid() {
    [ -f "$PID_FILE" ] || return 1
    pid=$(sed -n '1p' "$PID_FILE")
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

require_files() {
    for name in ATMKG_NEO4J_URI ATMKG_NEO4J_DATABASE ATMKG_NEO4J_USERNAME ATMKG_NEO4J_PASSWORD; do
        eval "value=\${$name:-}"
        [ -n "$value" ] || { echo "ERROR: $name is missing; copy tools/env.sh.example to tools/env.sh and fill it in." >&2; exit 2; }
    done
    [ -f "$JAR" ] || { echo "ERROR: Java artifact missing: $JAR" >&2; echo "Run mvn -DskipTests package once." >&2; exit 2; }
    [ -d "$LIB" ] && find "$LIB" -maxdepth 1 -name '*.jar' -print -quit | grep -q . || {
        echo "ERROR: runtime dependency directory is missing or empty: $LIB" >&2; exit 2;
    }
    for file in config/api.yaml config/sources.yaml mapping/字段映射.xlsx ontology/atm_knowledge_graph.ttl; do
        [ -f "$PROJECT_ROOT/$file" ] || { echo "ERROR: required project file is missing: $PROJECT_ROOT/$file" >&2; exit 2; }
    done
}

case "${1:-}" in
    start)
        resolve_java
        require_files
        if running_pid; then echo "ATMKG runtime already running: PID $pid"; exit 0; fi
        mkdir -p "$PROJECT_ROOT/runtime/state" "$LOG_DIR"
        nohup "$JAVA_COMMAND" -cp "$JAR:$LIB/*" org.atmkg.tools.KgServiceMain "$PROJECT_ROOT" \
            >"$LOG_FILE" 2>&1 &
        echo $! > "$PID_FILE"
        echo "ATMKG runtime started: PID $(cat "$PID_FILE")"
        echo "Log: $LOG_FILE"
        ;;
    stop)
        if ! running_pid; then rm -f "$PID_FILE"; echo "ATMKG runtime is not running; no active PID file."; exit 0; fi
        kill "$pid"
        rm -f "$PID_FILE"
        echo "ATMKG runtime stopped: PID $pid"
        ;;
    status)
        if running_pid; then echo "ATMKG runtime running: PID $pid"; else echo "ATMKG runtime stopped"; fi
        echo "Health: http://$ATMKG_API_HOST:$ATMKG_API_PORT/api/v1/health"
        ;;
    *) echo "Usage: tools/runtime.sh start|stop|status" >&2; exit 2 ;;
esac
