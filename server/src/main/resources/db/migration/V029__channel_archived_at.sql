ALTER TABLE channel ADD COLUMN archived_at TEXT;
CREATE INDEX IF NOT EXISTS idx_channel_archived_at ON channel(archived_at);
