-- Seed default voice mode
INSERT OR IGNORE INTO server_config (key, value, updated_at)
VALUES ('fold.livekit.mode', 'off', datetime('now'));
