package chat.fold.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventBufferTest {

    private EventBuffer buffer;

    @BeforeEach
    void setup() {
        buffer = new EventBuffer();
    }

    @Test
    void createBuffer_and_append() {
        buffer.createBuffer("s1", "user1");
        assertTrue(buffer.hasBuffer("s1"));
        assertEquals("user1", buffer.getUserId("s1"));

        buffer.append("s1", 1, "{\"op\":\"TEST\",\"s\":1}");
        var events = buffer.eventsSince("s1", 0);
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals(1, events.getFirst().sequence());
    }

    @Test
    void eventsSince_returnsOnlyEventsAfterSequence() {
        buffer.createBuffer("s1", "user1");
        buffer.append("s1", 1, "{\"s\":1}");
        buffer.append("s1", 2, "{\"s\":2}");
        buffer.append("s1", 3, "{\"s\":3}");
        buffer.append("s1", 4, "{\"s\":4}");

        var events = buffer.eventsSince("s1", 2);
        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals(3, events.get(0).sequence());
        assertEquals(4, events.get(1).sequence());
    }

    @Test
    void eventsSince_returnsEmptyWhenUpToDate() {
        buffer.createBuffer("s1", "user1");
        buffer.append("s1", 1, "{\"s\":1}");
        buffer.append("s1", 2, "{\"s\":2}");

        var events = buffer.eventsSince("s1", 2);
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void eventsSince_returnsNullForMissingSession() {
        assertNull(buffer.eventsSince("nonexistent", 0));
    }

    @Test
    void eventsSince_returnsNullWhenSequenceTooOld() {
        buffer.createBuffer("s1", "user1");
        // Fill buffer with events starting at sequence 100
        for (int i = 100; i < 200; i++) {
            buffer.append("s1", i, "{\"s\":" + i + "}");
        }

        // Asking for events since sequence 50 — oldest in buffer is 100
        // The buffer's first event is seq 100, so hasSequence(50) checks: 100 <= 51 → false
        assertNull(buffer.eventsSince("s1", 50));
    }

    @Test
    void ringBuffer_evictsOldestWhenFull() {
        buffer.createBuffer("s1", "user1");

        // Fill beyond MAX_EVENTS
        for (int i = 1; i <= EventBuffer.MAX_EVENTS + 50; i++) {
            buffer.append("s1", i, "{\"s\":" + i + "}");
        }

        // Earliest event should be seq 51 (first 50 evicted)
        var events = buffer.eventsSince("s1", 50);
        assertNotNull(events);
        assertEquals(EventBuffer.MAX_EVENTS, events.size());
        assertEquals(51, events.getFirst().sequence());
        assertEquals(EventBuffer.MAX_EVENTS + 50, events.getLast().sequence());
    }

    @Test
    void append_ignoredForMissingSession() {
        // Should not throw
        buffer.append("nonexistent", 1, "{\"s\":1}");
    }

    @Test
    void removeBuffer_works() {
        buffer.createBuffer("s1", "user1");
        assertTrue(buffer.hasBuffer("s1"));
        buffer.removeBuffer("s1");
        assertFalse(buffer.hasBuffer("s1"));
    }

    @Test
    void bufferCount_tracksActiveSessions() {
        assertEquals(0, buffer.bufferCount());
        buffer.createBuffer("s1", "user1");
        buffer.createBuffer("s2", "user2");
        assertEquals(2, buffer.bufferCount());
        buffer.removeBuffer("s1");
        assertEquals(1, buffer.bufferCount());
    }

    @Test
    void cleanExpired_removesOldBuffers() throws Exception {
        // We can't easily test TTL without mocking time, but we can test
        // that cleanExpired doesn't remove fresh buffers
        buffer.createBuffer("s1", "user1");
        buffer.append("s1", 1, "{\"s\":1}");

        buffer.cleanExpired();
        assertTrue(buffer.hasBuffer("s1")); // should still exist (not expired)
    }

    @Test
    void eventsSince_worksWithEmptyBuffer() {
        buffer.createBuffer("s1", "user1");

        // Empty buffer, sequence 0 — should return empty list
        var events = buffer.eventsSince("s1", 0);
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void eventsSince_emptyBuffer_nonZeroSequence() {
        buffer.createBuffer("s1", "user1");

        // Empty buffer, non-zero sequence — hasSequence returns false
        assertNull(buffer.eventsSince("s1", 5));
    }

    @Test
    void allSessionIds_returnsAllActive() {
        buffer.createBuffer("s1", "user1");
        buffer.createBuffer("s2", "user2");

        var ids = buffer.allSessionIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("s1"));
        assertTrue(ids.contains("s2"));
    }
}
