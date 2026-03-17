package chat.fold.service;

import chat.fold.db.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceServiceTest {

    private DatabaseService db;
    private MaintenanceService service;

    @BeforeEach
    void setup() throws Exception {
        db = mock(DatabaseService.class);
        when(db.query(anyString(), any())).thenReturn(List.of());

        service = new MaintenanceService();
        inject(service, "db", db);
        service.init();
    }

    @Test
    void initiallyDisabled() {
        assertFalse(service.isEnabled());
        assertNull(service.getMessage());
    }

    @Test
    void enableSetsEnabledTrueAndStoresMessage() {
        service.enable("Scheduled maintenance");

        assertTrue(service.isEnabled());
        assertEquals("Scheduled maintenance", service.getMessage());
    }

    @Test
    void disableSetsEnabledFalseAndClearsMessage() {
        service.enable("Scheduled maintenance");
        service.disable();

        assertFalse(service.isEnabled());
        assertNull(service.getMessage());
    }

    @Test
    void enableNullMessageIsAllowed() {
        service.enable(null);

        assertTrue(service.isEnabled());
        assertNull(service.getMessage());
    }

    @Test
    void enableWritesTrueToDb() {
        service.enable("msg");

        verify(db).execute(contains("'true'"), eq("maintenance_enabled"));
    }

    @Test
    void disableWritesFalseToDb() {
        service.disable();

        verify(db).execute(contains("'false'"), eq("maintenance_enabled"));
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
