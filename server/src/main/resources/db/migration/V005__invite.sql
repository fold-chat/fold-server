CREATE TABLE IF NOT EXISTS invite (
    id TEXT PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    creator_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    max_uses INTEGER,
    use_count INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_invite_code ON invite(code);
