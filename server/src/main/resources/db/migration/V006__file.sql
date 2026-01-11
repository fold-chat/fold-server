CREATE TABLE IF NOT EXISTS file (
    id TEXT PRIMARY KEY,
    original_name TEXT NOT NULL,
    stored_name TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    storage_type TEXT NOT NULL DEFAULT 'local',
    uploader_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    message_id TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    deleted_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_file_uploader_id ON file(uploader_id);
CREATE INDEX IF NOT EXISTS idx_file_stored_name ON file(stored_name);
