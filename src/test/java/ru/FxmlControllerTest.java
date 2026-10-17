package ru;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The controller behind {@code app.fxml}, loaded through the real FXML. Needs a JavaFX toolkit;
 * skipped where none can start (see {@link JavaFxToolkit}). The file-chooser handlers are not
 * tested: they open native dialogs, which the headless platform does not support (their
 * extension filters are, through {@code formatFilters}).
 *
 * <p>Conversions run on a background thread, so the tests start them on the JavaFX thread and
 * then wait until the START button is enabled again.
 */
class FxmlControllerTest {

    @TempDir
    Path dir;

    private FxmlController controller;

    @BeforeAll
    static void startToolkit() {
        JavaFxToolkit.assumeAvailable();
    }

    @BeforeEach
    void loadFxml() throws Exception {
        final FXMLLoader loader = new FXMLLoader(FxmlControllerTest.class.getResource("/app.fxml"));
        Parent root = JavaFxToolkit.onFxThread(() -> loader.<Parent>load());
        assertNotNull(root);
        controller = loader.getController();
        assertNotNull(controller);
    }

    /** Calls a handler on the JavaFX thread and waits until the conversion it started is over. */
    private void runAndWait(final String handler, final Button start, final TextField status) throws Exception {
        JavaFxToolkit.onFxThread(() -> TestSupport.call(controller, handler, TestSupport.types()));
        awaitIdle(start, status);
    }

    /** Waits until the START button is enabled again and the status no longer says "in progress". */
    private static void awaitIdle(final Button start, final TextField status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (JavaFxToolkit.onFxThread(() -> start.isDisabled()
                || FxmlController.STATUS_RUNNING.equals(status.getText()))) {
            if (System.nanoTime() > deadline) {
                fail("conversion did not finish; status " + status.getText());
            }
            Thread.sleep(10);
        }
    }

    private void convertTlkToXml() throws Exception {
        runAndWait("startExportTlkToXml", controller.startTlkToXml, controller.statusTlkToXml);
    }

    private void convertXmlToTlk() throws Exception {
        runAndWait("startExportXmlToTlk", controller.startXmlToTlk, controller.statusXmlToTlk);
    }

    @Test
    void fxmlInjectsTheControls() {
        assertNotNull(controller.buttonToSelectInputTlkFile);
        assertNotNull(controller.buttonToSelectOutputXmlFile);
        assertNotNull(controller.startTlkToXml);
        assertNotNull(controller.textFieldInputPathTlkToXml);
        assertNotNull(controller.textFieldOutputPathTlkToXml);
        assertNotNull(controller.statusTlkToXml);
        assertNotNull(controller.progressBarTlkToXml);

        assertNotNull(controller.buttonToSelectInputXmlFile);
        assertNotNull(controller.buttonToSelectOutputTlkFile);
        assertNotNull(controller.startXmlToTlk);
        assertNotNull(controller.textFieldInputPathXmlToTlk);
        assertNotNull(controller.textFieldOutputPathXmlToTlk);
        assertNotNull(controller.checkBoxDebugXmlToTlk);
        assertNotNull(controller.statusXmlToTlk);
        assertNotNull(controller.progressBarXmlToTlk);
        assertNotNull(controller.choiceBoxFormatTlkToXml);
        assertNotNull(controller.choiceBoxFormatXmlToTlk);
    }

    @Test
    void withExtensionReplacesOnlyTheFileNamesExtension() throws Exception {
        Class<?>[] types = TestSupport.types(java.io.File.class, String.class);
        for (String[] c : new String[][]{
                {"/a/game.tlk", "/a/game.xml"},
                {"/a.b/game", "/a.b/game.xml"},
                {"/a.b/game.v2.tlk", "/a.b/game.v2.xml"},
                {"/a/.hidden", "/a/.xml"},
                {"/a/game.", "/a/game.xml"}}) {
            assertEquals(c[1], TestSupport.call(controller, "withExtension", types, new java.io.File(c[0]), "xml"));
        }
    }

    @Test
    void initialState() {
        assertFalse(controller.textFieldOutputChosenTlkToXml);
        assertFalse(controller.textFieldOutputChosenXmlToTlk);
        assertFalse(controller.statusTlkToXml.isEditable());
        assertFalse(controller.statusXmlToTlk.isEditable());
        assertFalse(controller.checkBoxDebugXmlToTlk.isSelected());
        assertEquals(0.0, controller.progressBarTlkToXml.getProgress(), 0.0);
    }

    @Test
    void buttonsAreWiredToHandlers() {
        assertNotNull(controller.startTlkToXml.getOnAction());
        assertNotNull(controller.startXmlToTlk.getOnAction());
        assertNotNull(controller.buttonToSelectInputTlkFile.getOnAction());
        assertNotNull(controller.buttonToSelectOutputXmlFile.getOnAction());
        assertNotNull(controller.buttonToSelectInputXmlFile.getOnAction());
        assertNotNull(controller.buttonToSelectOutputTlkFile.getOnAction());
    }

    @Test
    void createXmlTabConvertsTlkToXml() throws Exception {
        Path tlk = new TlkTestFile().male(1, "Привет").maleRaw(-2, -1).female(1, "Hi").writeTo(dir.resolve("in.tlk"));
        final Path xml = dir.resolve("out.xml");
        controller.textFieldInputPathTlkToXml.setText(tlk.toString());
        controller.textFieldOutputPathTlkToXml.setText(xml.toString());

        convertTlkToXml();

        assertEquals(FxmlController.STATUS_DONE, controller.statusTlkToXml.getText());
        assertEquals(1.0, controller.progressBarTlkToXml.getProgress(), 0.0);
        assertFalse(controller.progressBarTlkToXml.progressProperty().isBound());
        ExportedXml parsed = ExportedXml.parse(xml);
        assertEquals(3, parsed.strings.size());
        assertEquals("Привет", parsed.strings.get(0).data);
        assertEquals("-1", parsed.strings.get(1).data);
        assertEquals("Hi", parsed.strings.get(2).data);
    }

    @Test
    void startButtonRunsTheConversionInTheBackground() throws Exception {
        Path tlk = new TlkTestFile().male(7, "fired").writeTo(dir.resolve("button.tlk"));
        Path xml = dir.resolve("button.xml");
        controller.textFieldInputPathTlkToXml.setText(tlk.toString());
        controller.textFieldOutputPathTlkToXml.setText(xml.toString());

        JavaFxToolkit.onFxThread(() -> {
            controller.startTlkToXml.fire();
            // the handler returns at once, with the tab locked until the conversion is over
            assertTrue(controller.startTlkToXml.isDisabled());
            assertTrue(controller.buttonToSelectInputTlkFile.isDisabled());
            assertTrue(controller.textFieldOutputPathTlkToXml.isDisabled());
            assertTrue(controller.choiceBoxFormatTlkToXml.isDisabled());
            assertEquals(FxmlController.STATUS_RUNNING, controller.statusTlkToXml.getText());
            assertTrue(controller.progressBarTlkToXml.progressProperty().isBound());
            return null;
        });
        awaitIdle(controller.startTlkToXml, controller.statusTlkToXml);

        assertEquals(FxmlController.STATUS_DONE, controller.statusTlkToXml.getText());
        assertFalse(controller.buttonToSelectInputTlkFile.isDisabled());
        assertFalse(controller.textFieldOutputPathTlkToXml.isDisabled());
        assertEquals("fired", ExportedXml.parse(xml).strings.get(0).data);
    }

    @Test
    void createTlkTabConvertsXmlToTlk() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        Path tlk = dir.resolve("out.tlk");
        controller.textFieldInputPathXmlToTlk.setText(xml.toString());
        controller.textFieldOutputPathXmlToTlk.setText(tlk.toString());

        convertXmlToTlk();

        assertEquals(FxmlController.STATUS_DONE, controller.statusXmlToTlk.getText());
        assertEquals(1.0, controller.progressBarXmlToTlk.getProgress(), 0.0);
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        assertEquals("Hello", TlkFileTest.refs(t).get(0).data);
        assertEquals("Female", TlkFileTest.refs(t).get(7).data);
    }

    @Test
    void testVersionCheckBoxAddsTheIdPrefix() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        Path tlk = dir.resolve("debug.tlk");
        controller.textFieldInputPathXmlToTlk.setText(xml.toString());
        controller.textFieldOutputPathXmlToTlk.setText(tlk.toString());
        controller.checkBoxDebugXmlToTlk.setSelected(true);

        convertXmlToTlk();

        assertEquals(FxmlController.STATUS_DONE, controller.statusXmlToTlk.getText());
        HuffmanCompression debug = new HuffmanCompression();
        debug.loadInputData(xml.toString(), FileFormat.XML, true);
        byte[] expected = HuffmanAccess.expectedDataBlock(debug);
        byte[] written = Files.readAllBytes(tlk);
        assertArrayEquals(expected, Arrays.copyOfRange(written, written.length - expected.length, written.length));
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        assertEquals("(#100) Hello", TlkFileTest.refs(t).get(0).data);
    }

    @Test
    void failedConversionIsReportedInTheStatus() throws Exception {
        Path missing = dir.resolve("missing.tlk");
        controller.textFieldInputPathTlkToXml.setText(missing.toString());
        controller.textFieldOutputPathTlkToXml.setText(dir.resolve("out.xml").toString());

        convertTlkToXml();

        assertEquals(FxmlController.STATUS_FAILED + "file not found: " + missing, controller.statusTlkToXml.getText());
        assertEquals(0.0, controller.progressBarTlkToXml.getProgress(), 0.0);
        assertFalse(controller.startTlkToXml.isDisabled());
    }

    @Test
    void invalidXmlIsReportedInTheStatus() throws Exception {
        Path xml = TestSupport.resource("/xml/old-version.xml");
        controller.textFieldInputPathXmlToTlk.setText(xml.toString());
        controller.textFieldOutputPathXmlToTlk.setText(dir.resolve("old.tlk").toString());

        convertXmlToTlk();

        String status = controller.statusXmlToTlk.getText();
        assertTrue(status.startsWith(FxmlController.STATUS_FAILED + "The XML file was written by TLK tool version 1.0.2"), status);
        assertFalse(Files.exists(dir.resolve("old.tlk")));
    }

    @Test
    void missingPathsAreReportedWithoutStarting() throws Exception {
        controller.textFieldOutputPathTlkToXml.setText(dir.resolve("out.xml").toString());
        JavaFxToolkit.onFxThread(() -> TestSupport.call(controller, "startExportTlkToXml", TestSupport.types()));
        assertEquals("Select an input file", controller.statusTlkToXml.getText());
        assertFalse(controller.startTlkToXml.isDisabled());

        controller.textFieldInputPathXmlToTlk.setText("  in.xml ");
        JavaFxToolkit.onFxThread(() -> TestSupport.call(controller, "startExportXmlToTlk", TestSupport.types()));
        assertEquals("Select an output file", controller.statusXmlToTlk.getText());
    }

    // ---------------------------------------------------------------- format selection

    @Test
    void formatChoiceBoxesOfferAllFormatsAndDefaultToXml() {
        for (javafx.scene.control.ChoiceBox<FileFormat> box : Arrays.asList(controller.choiceBoxFormatTlkToXml,
                controller.choiceBoxFormatXmlToTlk)) {
            assertEquals(Arrays.asList(FileFormat.XML, FileFormat.TXT, FileFormat.CSV), box.getItems());
            assertSame(FileFormat.XML, box.getValue());
        }
    }

    @Test
    void outputFormatFollowsTheOutputFileName() {
        TextField output = controller.textFieldOutputPathTlkToXml;
        output.setText(dir.resolve("out.csv").toString());
        assertSame(FileFormat.CSV, controller.choiceBoxFormatTlkToXml.getValue());
        output.setText(dir.resolve("out.TXT").toString());
        assertSame(FileFormat.TXT, controller.choiceBoxFormatTlkToXml.getValue());
        output.setText(dir.resolve("out.tlk").toString());
        output.setText("");
        assertSame(FileFormat.TXT, controller.choiceBoxFormatTlkToXml.getValue());
        assertEquals("", output.getText());
    }

    @Test
    void choosingAnOutputFormatChangesTheOutputExtension() {
        TextField output = controller.textFieldOutputPathTlkToXml;
        output.setText(dir.resolve("out.xml") + " ");
        controller.choiceBoxFormatTlkToXml.setValue(FileFormat.CSV);
        assertEquals(dir.resolve("out.csv").toString(), output.getText());
        controller.choiceBoxFormatTlkToXml.setValue(FileFormat.TXT);
        assertEquals(dir.resolve("out.txt").toString(), output.getText());

        // a path without a format extension is left alone
        output.setText(dir.resolve("out.dat").toString());
        controller.choiceBoxFormatTlkToXml.setValue(FileFormat.XML);
        assertEquals(dir.resolve("out.dat").toString(), output.getText());
        output.setText("");
        controller.choiceBoxFormatTlkToXml.setValue(FileFormat.CSV);
        assertEquals("", output.getText());
    }

    @Test
    void inputFormatFollowsTheInputFileName() {
        TextField input = controller.textFieldInputPathXmlToTlk;
        input.setText("strings.csv");
        assertSame(FileFormat.CSV, controller.choiceBoxFormatXmlToTlk.getValue());
        input.setText("strings.txt");
        assertSame(FileFormat.TXT, controller.choiceBoxFormatXmlToTlk.getValue());
        input.setText("strings.dat");
        assertSame(FileFormat.TXT, controller.choiceBoxFormatXmlToTlk.getValue());
        // choosing the format by hand does not touch the input path
        controller.choiceBoxFormatXmlToTlk.setValue(FileFormat.XML);
        assertEquals("strings.dat", input.getText());
        // nor does it change the output format of the other tab
        assertSame(FileFormat.XML, controller.choiceBoxFormatTlkToXml.getValue());
    }

    @Test
    void fileDialogFiltersOfferTheSelectedFormatFirst() {
        List<String> save = new ArrayList<>();
        for (FileChooser.ExtensionFilter f : FxmlController.formatFilters(FileFormat.CSV, false)) {
            save.add(f.getDescription() + " " + f.getExtensions());
        }
        assertEquals(Arrays.asList("CSV files [*.csv]", "XML files [*.xml]", "TXT files [*.txt]"), save);

        List<String> open = new ArrayList<>();
        for (FileChooser.ExtensionFilter f : FxmlController.formatFilters(FileFormat.TXT, true)) {
            open.add(f.getDescription() + " " + f.getExtensions());
        }
        assertEquals(Arrays.asList("XML, TXT and CSV files [*.txt, *.xml, *.csv]", "TXT files [*.txt]",
                "XML files [*.xml]", "CSV files [*.csv]"), open);
    }

    @Test
    void textFormatsConvertBothWays() throws Exception {
        Path tlk = new TlkTestFile().male(1, "Привет, \"мир\"").maleRaw(-2, -1).male(3, "two\nlines").female(1, "Hi")
                .writeTo(dir.resolve("in.tlk"));
        for (FileFormat format : Arrays.asList(FileFormat.TXT, FileFormat.CSV)) {
            Path text = dir.resolve("out." + format.getExtension());
            controller.textFieldInputPathTlkToXml.setText(tlk.toString());
            controller.choiceBoxFormatTlkToXml.setValue(format);
            controller.textFieldOutputPathTlkToXml.setText(text.toString());
            convertTlkToXml();
            assertEquals(FxmlController.STATUS_DONE, controller.statusTlkToXml.getText());
            assertTrue(TestSupport.readUtf8(text).contains(format == FileFormat.TXT ? "3: two\\nlines" : "3,2,true,\"two\nlines\""));

            Path back = dir.resolve("back-" + format + ".tlk");
            controller.textFieldInputPathXmlToTlk.setText(text.toString());
            assertSame(format, controller.choiceBoxFormatXmlToTlk.getValue());
            controller.textFieldOutputPathXmlToTlk.setText(back.toString());
            convertXmlToTlk();
            assertEquals(FxmlController.STATUS_DONE, controller.statusXmlToTlk.getText());
            TlkFile t = new TlkFile();
            t.loadTlkData(back.toString());
            assertEquals(Arrays.asList("Привет, \"мир\"", null, "two\nlines", "Hi"), TlkFileTest.texts(t));
        }
    }

    @Test
    void wrongInputFormatIsReportedInTheStatus() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        controller.textFieldInputPathXmlToTlk.setText(xml.toString());
        controller.choiceBoxFormatXmlToTlk.setValue(FileFormat.TXT);
        controller.textFieldOutputPathXmlToTlk.setText(dir.resolve("wrong.tlk").toString());

        convertXmlToTlk();

        String status = controller.statusXmlToTlk.getText();
        assertTrue(status.startsWith(FxmlController.STATUS_FAILED + "Missing header \"# TLKToolVersion=<version>\""), status);
        assertFalse(controller.choiceBoxFormatXmlToTlk.isDisabled());
    }

    @Test
    void describeGivesShortMessages() {
        assertEquals("file not found: a.tlk", FxmlController.describe(new NoSuchFileException("a.tlk")));
        assertEquals("access denied: b.xml", FxmlController.describe(new AccessDeniedException("b.xml")));
        assertEquals("broken", FxmlController.describe(new IOException("broken")));
        assertEquals("IllegalStateException", FxmlController.describe(new IllegalStateException()));
        assertEquals("IOException", FxmlController.describe(new IOException("")));
    }
}
