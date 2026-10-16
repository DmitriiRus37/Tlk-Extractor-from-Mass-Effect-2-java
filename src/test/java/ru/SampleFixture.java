package ru;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bundled sample {@code files/BIOGame_RUS.tlk}, loaded and exported to XML at most once per
 * test JVM and shared by all test classes (loading and exporting it takes about a second).
 *
 * <p>Tests that use it are skipped (not failed) if the sample file is missing. Callers must not
 * modify the shared {@link TlkFile}.
 */
final class SampleFixture {

    static final int MAGIC = 7040084;
    static final int VERSION = 3;
    static final int MIN_VERSION = 2;
    static final int MALE_COUNT = 103_970;
    static final int FEMALE_COUNT = 1_154;
    static final int REF_COUNT = MALE_COUNT + FEMALE_COUNT;
    static final int TREE_NODE_COUNT = 189;
    static final int DATA_LEN = 1_980_537;
    /** Refs with a negative bit offset (no text); exactly those with a negative string id. */
    static final int REFS_WITHOUT_TEXT = 47_747;

    private static TlkFile tlk;
    private static List<TlkFile.TlkStringRef> refs;
    private static Path tempDir;
    private static Path xml;
    private static ExportedXml parsedXml;

    private SampleFixture() {
    }

    static void assumeSamplePresent() {
        assumeTrue(Files.isRegularFile(TestSupport.samplePath()),
                "sample file not found: " + TestSupport.samplePath());
    }

    /** The loaded sample; shared, so do not modify it. */
    static synchronized TlkFile tlk() throws IOException {
        assumeSamplePresent();
        if (tlk == null) {
            TlkFile t = new TlkFile();
            t.loadTlkData(TestSupport.samplePath().toString());
            tlk = t;
        }
        return tlk;
    }

    /** The sample's string refs as an unmodifiable list, so that tests cannot change the shared data. */
    static synchronized List<TlkFile.TlkStringRef> refs() throws IOException {
        if (refs == null) {
            List<TlkFile.TlkStringRef> loaded = TestSupport.field(tlk(), "stringRefs");
            refs = Collections.unmodifiableList(new ArrayList<>(loaded));
        }
        return refs;
    }

    /** The sample exported with {@link FileFormat#XML}, in a temporary directory. */
    static synchronized Path xml() throws Exception {
        if (xml == null) {
            TlkFile t = tlk();
            tempDir = Files.createTempDirectory("tlk-sample-");
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    deleteRecursively(tempDir.toFile());
                }
            }));
            Path file = tempDir.resolve("BIOGame_RUS.xml");
            t.storeToFile(file.toString(), FileFormat.XML, null);
            xml = file;
        }
        return xml;
    }

    static synchronized ExportedXml parsedXml() throws Exception {
        if (parsedXml == null) {
            parsedXml = ExportedXml.parse(xml());
        }
        return parsedXml;
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
