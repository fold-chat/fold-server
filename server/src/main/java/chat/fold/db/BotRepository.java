package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class BotRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject DatabaseService db;

    // --- Token helpers ---

    /** Generate a new bot API token: fold_bot_<32 random bytes, url-safe base64> */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "fold_bot_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex hash of token for DB storage */
    public static String hashToken(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Bot CRUD ---

    /** Create a bot user row with is_bot=1. Uses "!" as dummy password hash (invalid, never matches). */
    public void createBot(String id, String username, String displayName) {
        db.execute(
                "INSERT INTO user (id, username, display_name, password_hash, is_bot, bot_enabled) VALUES (?, ?, ?, ?, 1, 1)",
                id, username, displayName != null ? displayName : username, "!"
        );
    }

    /** Create the initial bot token */
    public void createToken(String tokenId, String userId, String tokenHash) {
        db.execute(
                "INSERT INTO bot_token (id, user_id, token_hash) VALUES (?, ?, ?)",
                tokenId, userId, tokenHash
        );
    }

    /** Atomically replace the existing token with a new one */
    public void regenerateToken(String userId, String newTokenId, String newHash) {
        db.transactionVoid(tx -> {
            tx.execute("DELETE FROM bot_token WHERE user_id = ?", userId);
            tx.execute(
                    "INSERT INTO bot_token (id, user_id, token_hash) VALUES (?, ?, ?)",
                    newTokenId, userId, newHash
            );
        });
    }

    /** Look up a bot user by token hash. Returns the full user row if found and not deleted. */
    public Optional<Map<String, Object>> findByTokenHash(String hash) {
        var rows = db.query(
                "SELECT u.* FROM user u JOIN bot_token bt ON u.id = bt.user_id WHERE bt.token_hash = ? AND u.deleted_at IS NULL",
                hash
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Update last_used_at timestamp on the bot's token */
    public void updateLastUsed(String userId) {
        db.execute("UPDATE bot_token SET last_used_at = datetime('now') WHERE user_id = ?", userId);
    }

    /** List all bots with token metadata and roles */
    public List<Map<String, Object>> listBots() {
        return db.query("""
                SELECT u.id, u.username, u.display_name, u.avatar_url, u.is_bot, u.bot_enabled, u.created_at,
                       (SELECT bt.id FROM bot_token bt WHERE bt.user_id = u.id LIMIT 1) AS token_id,
                       (SELECT bt.created_at FROM bot_token bt WHERE bt.user_id = u.id LIMIT 1) AS token_created_at,
                       (SELECT bt.last_used_at FROM bot_token bt WHERE bt.user_id = u.id LIMIT 1) AS token_last_used_at,
                       JSON_GROUP_ARRAY(JSON_OBJECT('id', r.id, 'name', r.name, 'color', r.color)) AS roles
                FROM user u
                LEFT JOIN user_role ur ON u.id = ur.user_id
                LEFT JOIN role r ON ur.role_id = r.id
                WHERE u.is_bot = 1 AND u.deleted_at IS NULL
                GROUP BY u.id
                ORDER BY u.created_at
                """);
    }

    /** Find a single bot by user ID with token metadata and roles */
    public Optional<Map<String, Object>> findBotById(String id) {
        var rows = db.query("""
                SELECT u.id, u.username, u.display_name, u.avatar_url, u.is_bot, u.bot_enabled, u.created_at,
                       (SELECT bt.id FROM bot_token bt WHERE bt.user_id = u.id LIMIT 1) AS token_id,
                       (SELECT bt.created_at FROM bot_token bt WHERE bt.user_id = u.id LIMIT 1) AS token_created_at,
                       (SELECT bt.last_used_at FROM bot_token bt WHERE bt.user_id = u.id LIMIT 1) AS token_last_used_at,
                       JSON_GROUP_ARRAY(JSON_OBJECT('id', r.id, 'name', r.name, 'color', r.color)) AS roles
                FROM user u
                LEFT JOIN user_role ur ON u.id = ur.user_id
                LEFT JOIN role r ON ur.role_id = r.id
                WHERE u.id = ? AND u.is_bot = 1 AND u.deleted_at IS NULL
                GROUP BY u.id
                """, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Get the current token row for a bot (metadata only, not the hash) */
    public Optional<Map<String, Object>> getToken(String userId) {
        var rows = db.query(
                "SELECT id, created_at, last_used_at FROM bot_token WHERE user_id = ?",
                userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void setEnabled(String userId, boolean enabled) {
        db.execute("UPDATE user SET bot_enabled = ? WHERE id = ? AND is_bot = 1", enabled ? 1L : 0L, userId);
    }

    public void updateDisplayName(String userId, String displayName) {
        db.execute("UPDATE user SET display_name = ? WHERE id = ? AND is_bot = 1", displayName, userId);
    }

    public void updateAvatarUrl(String userId, String avatarUrl) {
        db.execute("UPDATE user SET avatar_url = ? WHERE id = ? AND is_bot = 1", avatarUrl, userId);
    }

    /** Hard-delete a bot user row — cascades to messages, reactions, user_role, bot_token via FK */
    public void hardDeleteBot(String userId) {
        db.execute("DELETE FROM user WHERE id = ? AND is_bot = 1", userId);
    }
}
