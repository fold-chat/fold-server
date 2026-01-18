CREATE TABLE IF NOT EXISTS thread (
    id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    parent_message_id TEXT REFERENCES message(id),
    title TEXT,
    author_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_activity_at TEXT NOT NULL DEFAULT (datetime('now')),
    locked INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_thread_channel_id ON thread(channel_id);
CREATE INDEX IF NOT EXISTS idx_thread_parent_message_id ON thread(parent_message_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_thread_parent_message_unique ON thread(parent_message_id) WHERE parent_message_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS thread_read_state (
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    thread_id TEXT NOT NULL REFERENCES thread(id) ON DELETE CASCADE,
    last_read_message_id TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, thread_id)
);
