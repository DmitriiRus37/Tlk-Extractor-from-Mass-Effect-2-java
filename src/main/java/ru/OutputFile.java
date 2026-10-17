package ru;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes an output file so that it is either complete or not touched: the content goes to a
 * temporary file next to it, which then replaces it in one move. A conversion that fails halfway
 * (a string the format cannot hold, a full disk) therefore leaves an existing file as it was and
 * creates no half-written one.
 */
final class OutputFile {

    /** Not instantiable. */
    private OutputFile() {
    }

    /** Writes the content of a file. */
    interface Content {
        /**
         * Writes the content.
         *
         * @param out the stream to write to; closed by the caller
         * @throws IOException if writing fails
         */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Writes a file, replacing an existing one only once the new content is complete. A symbolic
     * link is followed (its target is replaced, the link kept), and a replaced file's POSIX
     * permissions are kept.
     *
     * @param file    the file
     * @param content writes the content
     * @throws IOException if the file is a directory or cannot be written; the file is then
     *                     unchanged
     */
    static void write(Path file, Content content) throws IOException {
        Path target = Files.exists(file) ? file.toRealPath() : file.toAbsolutePath();
        if (Files.isDirectory(target)) {
            throw new FileSystemException(file.toString(), null, "Is a directory");
        }
        Path temp = target.resolveSibling(target.getFileName() + "." + Long.toHexString(
                ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE) + ".tmp");
        try {
            // not Files.createTempFile: its owner-only permissions would end up on the output
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                content.writeTo(out);
            }
            keepPermissions(target, temp);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Gives the new file the POSIX permissions of the file it replaces, if any and if the file
     * system has them.
     *
     * @param target the file being replaced
     * @param temp   the new content
     * @throws IOException if the permissions cannot be read or set
     */
    private static void keepPermissions(Path target, Path temp) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        if (view != null && Files.exists(target)) {
            Files.setPosixFilePermissions(temp, view.readAttributes().permissions());
        }
    }
}
