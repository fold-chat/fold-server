CREATE TABLE IF NOT EXISTS custom_emoji (
    id TEXT PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    file_id TEXT NOT NULL REFERENCES file(id) ON DELETE CASCADE,
    uploader_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_custom_emoji_name ON custom_emoji(name);
