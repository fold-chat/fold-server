package chat.fold.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceStorageTest {

    @TempDir Path tempDir;

    private FileService service;
    private Path filesDir;

    @BeforeEach
    void setup() throws Exception {
        service = new FileService();
        filesDir = tempDir.resolve("files");
        Files.createDirectories(filesDir);

        // Set private filesDir field directly (no CDI in unit tests)
        Field f = FileService.class.getDeclaredField("filesDir");
        f.setAccessible(true);
        f.set(service, filesDir);
    }

    @Test
    void resolveStoredPath_uses_two_char_prefix_directory() {
        var path = service.resolveStoredPath("77ef212e1954fcde.png");
        assertEquals(filesDir.resolve("77").resolve("ef212e1954fcde.png"), path);
    }

    @Test
    void resolveStoredPath_strips_prefix_from_filename() {
        var path = service.resolveStoredPath("abcdef1234.jpg");
        assertEquals("ab", path.getParent().getFileName().toString());
        assertEquals("cdef1234.jpg", path.getFileName().toString());
    }

    @Test
    void getFilePath_finds_file_in_layered_directory() throws IOException {
        String storedName = "aabbccdd1122.png";
        Path prefixDir = filesDir.resolve("aa");
        Files.createDirectories(prefixDir);
        Files.writeString(prefixDir.resolve("bbccdd1122.png"), "data");

        var result = service.getFilePath(storedName);
        assertTrue(result.isPresent());
        assertEquals(prefixDir.resolve("bbccdd1122.png"), result.get());
    }

    @Test
    void getFilePath_returns_empty_when_file_missing() {
        var result = service.getFilePath("deadbeef0000.png");
        assertTrue(result.isEmpty());
    }
}
