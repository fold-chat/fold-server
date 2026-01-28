CREATE TABLE IF NOT EXISTS voice_state (
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    self_mute INTEGER NOT NULL DEFAULT 0,
    self_deaf INTEGER NOT NULL DEFAULT 0,
    server_mute INTEGER NOT NULL DEFAULT 0,
    server_deaf INTEGER NOT NULL DEFAULT 0,
    joined_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_voice_state_channel ON voice_state(channel_id);

CREATE TABLE IF NOT EXISTS channel_voice_key (
    id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    encryption_key BLOB NOT NULL,
    key_index INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_channel_voice_key_channel ON channel_voice_key(channel_id);
