CREATE TABLE IF NOT EXISTS role (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    permissions INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL UNIQUE,
    color TEXT,
    is_default INTEGER NOT NULL DEFAULT 0,
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
-- Permissions are 64-bit bitmasks. See docs/permissions.md for full bit layout.
-- Owner: bypasses all checks (special role, not bitmask-dependent)
-- Admin: ADMINISTRATOR(bit31) + all channel/server/voice perms
-- Moderator: channel perms + KICK + BAN + CREATE_INVITES
-- Member: VIEW_CHANNEL(0) + SEND_MESSAGES(1) + MANAGE_OWN_MESSAGES(2) + UPLOAD_FILES(4) + ADD_REACTIONS(5) + CREATE_THREADS(8) + MANAGE_OWN_THREADS(9) + CREATE_INVITES(21) + CHANGE_NICKNAME(23)
INSERT OR IGNORE INTO role (id, name, permissions, position, color, is_default) VALUES
    ('owner', 'Owner', 0, 1, '#e74c3c', 0),
    ('admin', 'Admin', 272763916159, 2, '#3498db', 0),
    ('moderator', 'Moderator', 3671935, 3, '#2ecc71', 0),
    ('member', 'Member', 10486583, 4, NULL, 1);
