#!/bin/sh
set -e

DATA_DIR="${FOLD_DATA_DIR:-/alloc/data}"
mkdir -p "$DATA_DIR"

# Copy seed DB if available and no DB exists yet
if [ -f /app/seed/fold-seed.db ] && [ ! -f "$DATA_DIR/fold.db" ]; then
  echo "[preview] Copying seed database..."
  cp /app/seed/fold-seed.db "$DATA_DIR/fold.db"
else
  echo "[preview] Starting with fresh database (app will auto-setup)"
fi

exec java $JAVA_OPTS -jar /app/fold.jar
