-- Seed default runtime config for voice/video settings
INSERT OR IGNORE INTO server_config (key, value, updated_at) VALUES ('kith.livekit.max-participants', '50', datetime('now'));
INSERT OR IGNORE INTO server_config (key, value, updated_at) VALUES ('kith.livekit.e2ee', 'false', datetime('now'));
INSERT OR IGNORE INTO server_config (key, value, updated_at) VALUES ('kith.livekit.turn-enabled', 'false', datetime('now'));
