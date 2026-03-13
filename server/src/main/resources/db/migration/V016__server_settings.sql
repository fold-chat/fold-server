-- Seed server settings with defaults
INSERT OR IGNORE INTO server_config (key, value, updated_at) VALUES ('server_name', 'fold', datetime('now'));
INSERT OR IGNORE INTO server_config (key, value, updated_at) VALUES ('server_icon', NULL, datetime('now'));
INSERT OR IGNORE INTO server_config (key, value, updated_at) VALUES ('server_description', NULL, datetime('now'));
