#!/bin/sh
# Fix ownership of mounted volumes before dropping to the app user.
# Runs as root (no USER directive before ENTRYPOINT), then execs as 'ares'.
chown -R ares:ares /data /app/plugins 2>/dev/null || true
exec su-exec ares java $JAVA_OPTS -jar /app/app.jar "$@"
