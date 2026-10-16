package ru;

/**
 * Command-line entry point used by tests that need a separate JVM (e.g. with a different
 * {@code file.encoding}): converts a TLK file with {@link TlkFile}.
 *
 * <p>Arguments: input TLK path, output path, {@link FileFormat} name.
 */
public final class ExportMain {

    private ExportMain() {
    }

    public static void main(String[] args) throws Exception {
        TlkFile t = new TlkFile();
        t.loadTlkData(args[0]);
        t.storeToFile(args[1], FileFormat.valueOf(args[2]), null);
    }
}
