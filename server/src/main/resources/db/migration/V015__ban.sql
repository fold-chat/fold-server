-- Add ban columns to user table
ALTER TABLE user ADD COLUMN banned_at TEXT;
ALTER TABLE user ADD COLUMN banned_by TEXT REFERENCES user(id) ON DELETE SET NULL;
ALTER TABLE user ADD COLUMN ban_reason TEXT;

-- IP ban table
CREATE TABLE IF NOT EXISTS ip_ban (
    id TEXT PRIMARY KEY,
    ip_address TEXT NOT NULL UNIQUE,
    banned_by TEXT REFERENCES user(id) ON DELETE SET NULL,
    reason TEXT,
    expires_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_ip_ban_ip_address ON ip_ban(ip_address);
