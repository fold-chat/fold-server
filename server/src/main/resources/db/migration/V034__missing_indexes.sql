-- Session: refresh token lookup on every token refresh
CREATE INDEX IF NOT EXISTS idx_session_refresh_token_hash ON session(refresh_token_hash);

-- channel_read_state: unreadCounts joins on channel_id first
CREATE INDEX IF NOT EXISTS idx_channel_read_state_channel_id ON channel_read_state(channel_id);

-- thread_read_state: unreadThreads joins from thread side on thread_id
CREATE INDEX IF NOT EXISTS idx_thread_read_state_thread_id ON thread_read_state(thread_id);

-- message: covers paginate, unreadCounts, countMessagesAfter
-- All hot queries filter channel_id + thread_id IS NULL + ORDER BY id
CREATE INDEX IF NOT EXISTS idx_message_channel_thread_id ON message(channel_id, thread_id, id);
