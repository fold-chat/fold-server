CREATE TABLE IF NOT EXISTS message (
    id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    author_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    thread_id TEXT REFERENCES thread(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    edited_at TEXT,
    pinned INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_message_channel_id ON message(channel_id, created_at);
CREATE INDEX IF NOT EXISTS idx_message_author_id ON message(author_id);

CREATE INDEX IF NOT EXISTS idx_message_thread_id ON message(thread_id);

-- Add index on file.message_id for attachment lookups
CREATE INDEX IF NOT EXISTS idx_file_message_id ON file(message_id);
