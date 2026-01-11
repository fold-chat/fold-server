CREATE TABLE IF NOT EXISTS role (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    permissions INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL DEFAULT 0,
    color TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS user_role (
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    assigned_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_user_role_user_id ON user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role_id ON user_role(role_id);

-- Seed default roles
-- Permissions bitmask: 1=VIEW, 2=SEND_MESSAGE, 4=MANAGE_MESSAGES, 8=MANAGE_CHANNELS,
--   16=MANAGE_ROLES, 32=MANAGE_SERVER, 64=KICK, 128=BAN, 256=INVITE, 512=UPLOAD_FILES
-- Owner: all (1023), Admin: all except MANAGE_SERVER (991), Moderator: view+send+manage_messages+kick+ban+invite+upload (455), Member: view+send+invite+upload (771)
INSERT OR IGNORE INTO role (id, name, permissions, position) VALUES
    ('owner', 'Owner', 1023, 100),
    ('admin', 'Admin', 991, 80),
    ('moderator', 'Moderator', 455, 60),
    ('member', 'Member', 771, 0);
