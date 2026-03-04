-- Seed default voice mode
INSERT OR IGNORE INTO server_config (key, value, updated_at)
VALUES ('kith.livekit.mode', 'off', datetime('now'));
