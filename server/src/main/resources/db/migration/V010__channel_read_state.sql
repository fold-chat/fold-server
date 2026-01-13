CREATE TABLE IF NOT EXISTS channel_read_state (
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    last_read_message_id TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, channel_id)
);
