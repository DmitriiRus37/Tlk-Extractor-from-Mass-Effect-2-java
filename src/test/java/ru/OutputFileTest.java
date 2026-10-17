package ru;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OutputFileTest {

    @TempDir
    Path dir;

    private static OutputFile.Content text(String s) {
        return out -> out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private long filesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    @Test
    void writesANewFileAndReplacesAnOldOne() throws IOException {
        Path file = dir.resolve("out.xml");
        OutputFile.write(file, text("first"));
        assertEquals("first", Files.readString(file));
        OutputFile.write(file, text("second"));
        assertEquals("second", Files.readString(file));
        assertEquals(1, filesIn(dir));
    }

    @Test
    void aFailedWriteLeavesTheOldFileAndNoTemporaryFile() throws IOException {
        Path file = Files.writeString(dir.resolve("out.xml"), "old");
        IOException e = assertThrows(IOException.class, () -> OutputFile.write(file, out -> {
            out.write("half".getBytes(StandardCharsets.UTF_8));
            throw new IOException("cannot be written");
        }));
        assertEquals("cannot be written", e.getMessage());
        assertEquals("old", Files.readString(file));
        assertEquals(1, filesIn(dir));

        Path missing = dir.resolve("new.xml");
        assertThrows(IOException.class, () -> OutputFile.write(missing, out -> {
            throw new IOException("fails");
        }));
        assertTrue(Files.notExists(missing));
        assertEquals(1, filesIn(dir));
    }

    @Test
    void aDirectoryIsNotReplaced() throws IOException {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        assertThrows(IOException.class, () -> OutputFile.write(sub, text("x")));
        assertTrue(Files.isDirectory(sub));
    }

    @Test
    void permissionsAreTheUsualOnesAndThoseOfAReplacedFileAreKept() throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path plain = Files.writeString(dir.resolve("plain.txt"), "x");
        Path file = dir.resolve("new.txt");
        OutputFile.write(file, text("x"));
        assertEquals(Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(file));

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
        OutputFile.write(file, text("y"));
        assertEquals(PosixFilePermissions.fromString("rw-rw-r--"), Files.getPosixFilePermissions(file));
    }

    @Test
    void aSymbolicLinkIsFollowedAndKept() throws IOException {
        Path target = Files.writeString(dir.resolve("target.txt"), "old");
        Path link;
        try {
            link = Files.createSymbolicLink(dir.resolve("link.txt"), target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
            return;
        }
        OutputFile.write(link, text("new"));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(target));
    }
}
