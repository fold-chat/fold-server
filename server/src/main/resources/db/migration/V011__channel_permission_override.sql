CREATE TABLE IF NOT EXISTS channel_permission_override (
    id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    allow INTEGER NOT NULL DEFAULT 0,
    deny INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(channel_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_permission_override_channel_id ON channel_permission_override(channel_id);
CREATE INDEX IF NOT EXISTS idx_channel_permission_override_role_id ON channel_permission_override(role_id);
