-- FTS5 full-text search index on messages (external content table)
CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
    content,
    channel_id UNINDEXED,
    author_id UNINDEXED,
    thread_id UNINDEXED,
    content='message',
    content_rowid='rowid'
);

-- Keep FTS in sync with message table
CREATE TRIGGER IF NOT EXISTS message_fts_insert AFTER INSERT ON message BEGIN
    INSERT INTO message_fts(rowid, content, channel_id, author_id, thread_id)
    VALUES (new.rowid, new.content, new.channel_id, new.author_id, new.thread_id);
END;

CREATE TRIGGER IF NOT EXISTS message_fts_update AFTER UPDATE OF content ON message BEGIN
    INSERT INTO message_fts(message_fts, rowid, content, channel_id, author_id, thread_id)
    VALUES ('delete', old.rowid, old.content, old.channel_id, old.author_id, old.thread_id);
    INSERT INTO message_fts(rowid, content, channel_id, author_id, thread_id)
    VALUES (new.rowid, new.content, new.channel_id, new.author_id, new.thread_id);
END;

CREATE TRIGGER IF NOT EXISTS message_fts_delete AFTER DELETE ON message BEGIN
    INSERT INTO message_fts(message_fts, rowid, content, channel_id, author_id, thread_id)
    VALUES ('delete', old.rowid, old.content, old.channel_id, old.author_id, old.thread_id);
END;

-- Backfill existing messages
INSERT INTO message_fts(rowid, content, channel_id, author_id, thread_id)
SELECT rowid, content, channel_id, author_id, thread_id FROM message;
