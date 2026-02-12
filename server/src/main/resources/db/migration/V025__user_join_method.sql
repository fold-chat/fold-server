ALTER TABLE user ADD COLUMN join_method TEXT NOT NULL DEFAULT 'unknown';
ALTER TABLE user ADD COLUMN joined_via_invite_id TEXT REFERENCES invite(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_user_joined_via_invite_id ON user(joined_via_invite_id);
