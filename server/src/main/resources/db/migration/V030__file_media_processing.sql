ALTER TABLE file ADD COLUMN thumbnail_stored_name TEXT;
ALTER TABLE file ADD COLUMN processing_status TEXT NOT NULL DEFAULT 'complete';
ALTER TABLE file ADD COLUMN duration_seconds REAL;
ALTER TABLE file ADD COLUMN width INTEGER;
ALTER TABLE file ADD COLUMN height INTEGER;
