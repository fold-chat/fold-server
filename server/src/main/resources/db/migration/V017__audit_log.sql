-- Audit log table
CREATE TABLE IF NOT EXISTS audit_log (
    id TEXT PRIMARY KEY,
    actor_id TEXT,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    details TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (actor_id) REFERENCES user(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_id ON audit_log(actor_id);
