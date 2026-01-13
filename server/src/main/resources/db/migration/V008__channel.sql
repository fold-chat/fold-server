CREATE TABLE IF NOT EXISTS channel (
    id TEXT PRIMARY KEY,
    category_id TEXT REFERENCES category(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'TEXT',
    topic TEXT,
    description TEXT,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    settings TEXT
);

CREATE INDEX IF NOT EXISTS idx_channel_category_id ON channel(category_id);
CREATE INDEX IF NOT EXISTS idx_channel_position ON channel(position);
