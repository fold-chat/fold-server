-- DM conversation participants
CREATE TABLE IF NOT EXISTS dm_conversation (
    channel_id TEXT NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    last_activity_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (channel_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_dm_conversation_user_id ON dm_conversation(user_id);

-- DM blocking
CREATE TABLE IF NOT EXISTS dm_block (
    blocker_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    blocked_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_dm_block_blocked_id ON dm_block(blocked_id);

-- Grant INITIATE_DM (bit 26 = 67108864) to Member, Moderator, Admin
-- Member: 10486583 | 67108864 = 77595447
UPDATE role SET permissions = permissions | 67108864 WHERE id IN ('member', 'moderator', 'admin');
