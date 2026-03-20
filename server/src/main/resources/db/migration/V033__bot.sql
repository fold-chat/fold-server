-- Bot support: add is_bot and bot_enabled flags to user
ALTER TABLE user ADD COLUMN is_bot INTEGER NOT NULL DEFAULT 0;
ALTER TABLE user ADD COLUMN bot_enabled INTEGER NOT NULL DEFAULT 1;

-- Bot API token table: one active token per bot, stored as SHA-256 hash
CREATE TABLE IF NOT EXISTS bot_token (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_used_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_bot_token_user_id ON bot_token(user_id);
CREATE INDEX IF NOT EXISTS idx_bot_token_hash ON bot_token(token_hash);
