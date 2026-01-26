-- Add mention_count to channel_read_state
ALTER TABLE channel_read_state ADD COLUMN mention_count INTEGER NOT NULL DEFAULT 0;

-- Add mention_count to thread_read_state
ALTER TABLE thread_read_state ADD COLUMN mention_count INTEGER NOT NULL DEFAULT 0;
