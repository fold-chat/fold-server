#!/bin/sh
set -e

DATA_DIR="${KITH_DATA_DIR:-/alloc/data}"
mkdir -p "$DATA_DIR"

# Copy seed DB if available and no DB exists yet
if [ -f /app/seed/kith-seed.db ] && [ ! -f "$DATA_DIR/kith.db" ]; then
  echo "[preview] Copying seed database..."
  cp /app/seed/kith-seed.db "$DATA_DIR/kith.db"
else
  echo "[preview] Starting with fresh database (app will auto-setup)"
fi

exec java $JAVA_OPTS -jar /app/kith.jar
