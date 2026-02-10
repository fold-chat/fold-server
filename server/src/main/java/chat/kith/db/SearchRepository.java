package chat.kith.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SearchRepository {

    @Inject
    DatabaseService db;

    /**
     * Full-text search over messages using FTS5.
     * Filters results to only the given viewable channel IDs.
     */
    public List<Map<String, Object>> search(
            String query,
            Set<String> viewableChannelIds,
            String channelId,
            String authorId,
            String before,
            String after,
            boolean hasFile,
            int limit
    ) {
        if (viewableChannelIds.isEmpty()) return List.of();

        var params = new ArrayList<Object>();
        var conditions = new ArrayList<String>();

        // FTS5 MATCH
        conditions.add("f.content MATCH ?");
        params.add(query);

        // Restrict to viewable channels
        if (channelId != null && viewableChannelIds.contains(channelId)) {
            conditions.add("f.channel_id = ?");
            params.add(channelId);
        } else {
            var placeholders = String.join(",", viewableChannelIds.stream().map(id -> "?").toList());
            conditions.add("f.channel_id IN (" + placeholders + ")");
            params.addAll(viewableChannelIds);
        }

        if (authorId != null) {
            conditions.add("f.author_id = ?");
            params.add(authorId);
        }

        // Date filters — compare against message.created_at via join
        if (before != null) {
            conditions.add("m.created_at < ?");
            params.add(before);
        }
        if (after != null) {
            conditions.add("m.created_at > ?");
            params.add(after);
        }

        // has:file — messages that have at least one attachment
        if (hasFile) {
            conditions.add("EXISTS (SELECT 1 FROM file fi WHERE fi.message_id = m.id AND fi.deleted_at IS NULL)");
        }

        params.add(limit);

        String sql = """
                SELECT m.*, u.username AS author_username, u.display_name AS author_display_name,
                       u.avatar_url AS author_avatar_url, snippet(message_fts, 0, '<mark>', '</mark>', '…', 48) AS snippet
                FROM message_fts f
                JOIN message m ON m.rowid = f.rowid
                JOIN user u ON m.author_id = u.id"""
                + " WHERE " + String.join(" AND ", conditions)
                + " ORDER BY f.rank LIMIT ?";

        return db.query(sql, params.toArray());
    }
}
