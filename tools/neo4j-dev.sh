#!/bin/sh

CONTAINER=atmkg-neo4j-dev
IMAGE=neo4j:5.26.0
DATA_VOLUME=atmkg-neo4j-dev-data
LOGS_VOLUME=atmkg-neo4j-dev-logs

for name in ATMKG_NEO4J_URI ATMKG_NEO4J_DATABASE ATMKG_NEO4J_USERNAME ATMKG_NEO4J_PASSWORD; do
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "ERROR: set $name before using neo4j-dev.sh" >&2
        exit 2
    fi
done

case "${1:-}" in
    start)
        if docker ps --format '{{.Names}}' | grep -Fx "$CONTAINER" >/dev/null 2>&1; then
            echo "Neo4j dev container already running: $CONTAINER"
        elif docker ps -a --format '{{.Names}}' | grep -Fx "$CONTAINER" >/dev/null 2>&1; then
            docker start "$CONTAINER" >/dev/null || exit 1
        else
            docker volume create "$DATA_VOLUME" >/dev/null || exit 1
            docker volume create "$LOGS_VOLUME" >/dev/null || exit 1
            docker run -d --name "$CONTAINER" --restart no \
                -p 17474:7474 -p 17687:7687 \
                -e "NEO4J_AUTH=$ATMKG_NEO4J_USERNAME/$ATMKG_NEO4J_PASSWORD" \
                -v "$DATA_VOLUME:/data" -v "$LOGS_VOLUME:/logs" "$IMAGE" || exit 1
        fi
        "$0" status
        ;;
    stop) docker stop "$CONTAINER" ;;
    status)
        docker ps -a --filter "name=^/$CONTAINER$" --format 'container={{.Names}} status={{.Status}}'
        echo "Browser: http://127.0.0.1:17474"
        echo "Bolt: $ATMKG_NEO4J_URI"
        echo "Database: $ATMKG_NEO4J_DATABASE"
        echo "Username: $ATMKG_NEO4J_USERNAME"
        echo "Password: supplied by ATMKG_NEO4J_PASSWORD (not printed)"
        ;;
    reset)
        docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
        docker volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
        docker volume rm "$LOGS_VOLUME" >/dev/null 2>&1 || true
        echo "Neo4j dev container and disposable volumes removed."
        ;;
    *) echo "Usage: neo4j-dev.sh start|stop|status|reset" >&2; exit 2 ;;
esac
