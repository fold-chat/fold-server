package chat.fold.config;

import chat.fold.db.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeConfigServiceTest {

    private DatabaseService db;
    private RuntimeConfigService service;

    @BeforeEach
    void setup() throws Exception {
        db = mock(DatabaseService.class);
        service = new RuntimeConfigService();
        inject(service, "db", db);
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void seedConfig(Map<String, String> entries) {
        var rows = entries.entrySet().stream()
                .map(e -> Map.<String, Object>of("key", e.getKey(), "value", e.getValue()))
                .toList();
        when(db.query("SELECT key, value FROM server_config WHERE key LIKE 'fold.%'")).thenReturn(rows);
        service.refresh();
    }

    // --- getString ---

    @Test
    void getString_returnsValueFromCache() {
        seedConfig(Map.of("fold.livekit.e2ee", "true"));
        assertEquals("true", service.getString("fold.livekit.e2ee", "false"));
    }

    @Test
    void getString_returnsDefaultWhenKeyMissing() {
        seedConfig(Map.of());
        assertEquals("fallback", service.getString("fold.missing", "fallback"));
    }

    // --- getInt ---

    @Test
    void getInt_parsesIntValue() {
        seedConfig(Map.of("fold.livekit.max-participants", "100"));
        assertEquals(100, service.getInt("fold.livekit.max-participants", 50));
    }

    @Test
    void getInt_returnsDefaultOnMissing() {
        seedConfig(Map.of());
        assertEquals(50, service.getInt("fold.livekit.max-participants", 50));
    }

    @Test
    void getInt_returnsDefaultOnInvalidValue() {
        seedConfig(Map.of("fold.livekit.max-participants", "not-a-number"));
        assertEquals(50, service.getInt("fold.livekit.max-participants", 50));
    }

    // --- getBoolean ---

    @Test
    void getBoolean_parsesTrue() {
        seedConfig(Map.of("fold.livekit.e2ee", "true"));
        assertTrue(service.getBoolean("fold.livekit.e2ee", false));
    }

    @Test
    void getBoolean_parsesFalse() {
        seedConfig(Map.of("fold.livekit.e2ee", "false"));
        assertFalse(service.getBoolean("fold.livekit.e2ee", true));
    }

    @Test
    void getBoolean_parsesOne() {
        seedConfig(Map.of("fold.livekit.e2ee", "1"));
        assertTrue(service.getBoolean("fold.livekit.e2ee", false));
    }

    @Test
    void getBoolean_returnsDefaultOnMissing() {
        seedConfig(Map.of());
        assertTrue(service.getBoolean("fold.livekit.e2ee", true));
        assertFalse(service.getBoolean("fold.livekit.e2ee", false));
    }

    // --- refresh ---

    @Test
    void refresh_updatesCache() {
        seedConfig(Map.of("fold.livekit.max-participants", "50"));
        assertEquals(50, service.getInt("fold.livekit.max-participants", 0));

        // Simulate DB update
        seedConfig(Map.of("fold.livekit.max-participants", "100"));
        assertEquals(100, service.getInt("fold.livekit.max-participants", 0));
    }

    @Test
    void refresh_removesDeletedKeys() {
        seedConfig(Map.of("fold.livekit.e2ee", "true"));
        assertTrue(service.getBoolean("fold.livekit.e2ee", false));

        // Key removed from DB
        seedConfig(Map.of());
        assertFalse(service.getBoolean("fold.livekit.e2ee", false));
    }

    // --- getOverridableConfig ---

    @Test
    void getOverridableConfig_returnsOnlyWhitelistedKeys() {
        seedConfig(Map.of(
                "fold.livekit.max-participants", "50",
                "fold.livekit.e2ee", "false",
                "fold.livekit.turn-enabled", "false",
                "fold.some.other.key", "value"
        ));

        var config = service.getOverridableConfig();
        assertEquals(RuntimeConfigService.WHITELISTED_KEYS.size(), config.size());
        assertEquals("50", config.get("fold.livekit.max-participants"));
        assertEquals("false", config.get("fold.livekit.e2ee"));
        assertEquals("false", config.get("fold.livekit.turn-enabled"));
        assertFalse(config.containsKey("fold.some.other.key"));
    }

    @Test
    void getOverridableConfig_returnsNullForUnsetWhitelistedKeys() {
        seedConfig(Map.of());
        var config = service.getOverridableConfig();
        for (var key : RuntimeConfigService.WHITELISTED_KEYS) {
            assertTrue(config.containsKey(key));
            assertNull(config.get(key));
        }
    }

    // --- isWhitelisted ---

    @Test
    void isWhitelisted_trueForAllowedKeys() {
        assertTrue(service.isWhitelisted("fold.livekit.max-participants"));
        assertTrue(service.isWhitelisted("fold.livekit.e2ee"));
        assertTrue(service.isWhitelisted("fold.livekit.turn-enabled"));
    }

    @Test
    void isWhitelisted_falseForOtherKeys() {
        assertFalse(service.isWhitelisted("fold.db.path"));
        assertFalse(service.isWhitelisted("fold.auth.jwt-secret"));
        assertFalse(service.isWhitelisted("random.key"));
    }

    // --- Null handling ---

    @Test
    void refresh_skipsNullValues() {
        var rows = List.<Map<String, Object>>of(
                Map.of("key", "fold.livekit.e2ee"),  // no "value" key
                Map.of("key", "fold.livekit.max-participants", "value", "50")
        );
        when(db.query("SELECT key, value FROM server_config WHERE key LIKE 'fold.%'")).thenReturn(rows);
        service.refresh();

        // Null value key should not be in cache
        assertEquals("default", service.getString("fold.livekit.e2ee", "default"));
        assertEquals(50, service.getInt("fold.livekit.max-participants", 0));
    }
}
