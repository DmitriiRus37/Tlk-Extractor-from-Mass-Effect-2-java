package ru;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Runs the conversion code once in the background while the user picks a file, so that the JIT
 * has compiled much of it when the real conversion starts; the tool usually does a single
 * conversion per JVM, which otherwise runs mostly interpreted.
 *
 * <p>It converts a small generated file (about 150 KB of TXT, no game text) to TLK, that to XML,
 * TXT and CSV, and each of those back to TLK, in a temporary directory. On {@code BIOGame_RUS.tlk}
 * (JDK 25), with ~1.5 s before the real conversion, the first load/export/import round of all
 * formats took ~650 ms instead of ~800 ms or more without an AOT cache, and ~410 instead of
 * ~525 ms with {@code run.sh}'s cache. A conversion started at once was not slower (~695 vs
 * ~800 ms, ~510 vs ~545 ms).
 */
final class WarmUp {

    /** Number of entries of the generated file. */
    private static final int ENTRIES = 3000;

    /** Not instantiable. */
    private WarmUp() {
    }

    /** Starts the warm-up on a low-priority daemon thread; failures are ignored. */
    static void start() {
        Thread thread = new Thread(() -> {
            try {
                run();
            } catch (IOException | RuntimeException e) {
                // only a warm-up: the real conversion reports its own problems
            }
        }, "tlk-warm-up");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Converts a generated file through every format in a temporary directory, which is deleted
     * afterwards.
     *
     * @throws IOException if a conversion fails
     */
    static void run() throws IOException {
        Path dir = Files.createTempDirectory("tlk-warm-up");
        try {
            Path txt = Files.writeString(dir.resolve("generated.txt"), generatedTxt());
            HuffmanCompression compression = new HuffmanCompression();
            compression.loadInputData(txt.toString(), FileFormat.TXT, false);
            Path tlk = dir.resolve("generated.tlk");
            compression.saveToTlkFile(tlk.toString(), progress -> { });
            convert(tlk, dir);
        } finally {
            delete(dir);
        }
    }

    /**
     * Converts a TLK file to XML, TXT and CSV and each of them back to TLK, as the two tabs of
     * the GUI do; also used by {@link AotTraining}.
     *
     * @param tlk the TLK file
     * @param dir directory for the output files
     * @throws IOException if a conversion fails
     */
    static void convert(Path tlk, Path dir) throws IOException {
        TlkFile file = new TlkFile();
        file.loadTlkData(tlk.toString());
        for (FileFormat format : FileFormat.values()) {
            Path text = dir.resolve("round." + format.getExtension());
            file.storeToFile(text.toString(), format, progress -> { });
            HuffmanCompression compression = new HuffmanCompression();
            compression.loadInputData(text.toString(), format, false);
            compression.saveToTlkFile(dir.resolve("round-" + format.name() + ".tlk").toString(), progress -> { });
        }
    }

    /**
     * Deletes a directory and everything in it.
     *
     * @param dir the directory
     * @throws IOException if it cannot be listed
     */
    static void delete(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /**
     * Returns a TXT file of {@value #ENTRIES} entries with texts made of Cyrillic and Latin words,
     * line breaks, quotes, commas and the characters XML escapes, some entries without text, some
     * repeated texts and some ids with a female variant.
     *
     * @return the file content
     */
    private static String generatedTxt() {
        Random random = new Random(1);
        String[] words = {"Шепард", "Нормандия", "Цитадель", "Жнецы", "команда", "миссия", "капитан",
                          "Shepard", "Normandy", "Citadel", "mission", "squad", "\"quoted\"", "a,b",
                          "R&D", "<tag>", "line\\nbreak", "tab\\there", "100%", "—"};
        StringBuilder txt = new StringBuilder(160_000).append(TxtFormat.HEADER).append("=")
                .append(TlkFile.TOOL_VERSION).append("\r\n");
        String previous = "";
        for (int i = 0; i < ENTRIES; i++) {
            int id = i % 10 == 9 ? i - 1 : i;
            txt.append(id).append(": ");
            int kind = random.nextInt(20);
            if (kind == 0) {
                txt.append(TxtFormat.NO_TEXT);
            } else if (kind == 1) {
                txt.append(previous);
            } else {
                StringBuilder text = new StringBuilder();
                for (int w = 1 + random.nextInt(10); w > 0; w--) {
                    text.append(words[random.nextInt(words.length)]).append(' ');
                }
                previous = text.append(i).toString();
                txt.append(previous);
            }
            txt.append("\r\n");
        }
        return txt.toString();
    }
}
