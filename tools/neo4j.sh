#!/bin/sh

if [ -z "${ATMKG_NEO4J_HOME:-}" ]; then
    echo "ERROR: ATMKG_NEO4J_HOME is not set." >&2
    echo "Set ATMKG_NEO4J_HOME to the Neo4j installation directory." >&2
    echo "Manual fallback: cd <NEO4J_HOME>/bin" >&2
    echo "Then run: ./neo4j console" >&2
    exit 2
fi

NEO4J_COMMAND="$ATMKG_NEO4J_HOME/bin/neo4j"
if [ ! -x "$NEO4J_COMMAND" ]; then
    echo "ERROR: Neo4j command is not executable: $NEO4J_COMMAND" >&2
    echo "Set ATMKG_NEO4J_HOME to the directory that contains bin/neo4j." >&2
    echo "Manual fallback: cd <NEO4J_HOME>/bin" >&2
    echo "Then run: ./neo4j console" >&2
    exit 2
fi

case "${1:-}" in
    start|stop|status|console)
        exec "$NEO4J_COMMAND" "$1"
        ;;
    *)
        echo "Usage: neo4j.sh start|stop|status|console" >&2
        exit 2
        ;;
esac
