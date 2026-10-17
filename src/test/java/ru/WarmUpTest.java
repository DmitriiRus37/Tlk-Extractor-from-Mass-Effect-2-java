package ru;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmUpTest {

    @Test
    void runConvertsTheGeneratedFileThroughEveryFormat() {
        assertDoesNotThrow(WarmUp::run);
    }

    @Test
    void convertWritesEveryFormatAndDeleteRemovesTheDirectory(@TempDir Path dir) throws IOException {
        Path work = Files.createDirectory(dir.resolve("work"));
        WarmUp.convert(TestSupport.samplePath(), work);
        for (FileFormat format : FileFormat.values()) {
            assertTrue(Files.size(work.resolve("round." + format.getExtension())) > 1_000_000);
            assertTrue(Files.exists(work.resolve("round-" + format.name() + ".tlk")));
        }
        WarmUp.delete(work);
        assertTrue(Files.notExists(work));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }
}
