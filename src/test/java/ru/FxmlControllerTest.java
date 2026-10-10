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
        runAndWait("startExportTlkToXml", controller.getStartTlkToXml(), controller.getStatusTlkToXml());
    }

    private void convertXmlToTlk() throws Exception {
        runAndWait("startExportXmlToTlk", controller.getStartXmlToTlk(), controller.getStatusXmlToTlk());
    }

    @Test
    void fxmlInjectsTheControls() {
        assertNotNull(controller.getButtonToSelectInputTlkFile());
        assertNotNull(controller.getButtonToSelectOutputXmlFile());
        assertNotNull(controller.getStartTlkToXml());
        assertNotNull(controller.getTextFieldInputPathTlkToXml());
        assertNotNull(controller.getTextFieldOutputPathTlkToXml());
        assertNotNull(controller.getStatusTlkToXml());
        assertNotNull(controller.getProgressBarTlkToXml());

        assertNotNull(controller.getButtonToSelectInputXmlFile());
        assertNotNull(controller.getButtonToSelectOutputTlkFile());
        assertNotNull(controller.getStartXmlToTlk());
        assertNotNull(controller.getTextFieldInputPathXmlToTlk());
        assertNotNull(controller.getTextFieldOutputPathXmlToTlk());
        assertNotNull(controller.getCheckBoxDebugXmlToTlk());
        assertNotNull(controller.getStatusXmlToTlk());
        assertNotNull(controller.getProgressBarXmlToTlk());
        assertNotNull(controller.getChoiceBoxFormatTlkToXml());
        assertNotNull(controller.getChoiceBoxFormatXmlToTlk());
    }

    @Test
    void initialState() {
        assertFalse(controller.isTextFieldOutputChosenTlkToXml());
        assertFalse(controller.isTextFieldOutputChosenXmlToTlk());
        assertFalse(controller.getStatusTlkToXml().isEditable());
        assertFalse(controller.getStatusXmlToTlk().isEditable());
        assertFalse(controller.getCheckBoxDebugXmlToTlk().isSelected());
        assertEquals(0.0, controller.getProgressBarTlkToXml().getProgress(), 0.0);
    }

    @Test
    void buttonsAreWiredToHandlers() {
        assertNotNull(controller.getStartTlkToXml().getOnAction());
        assertNotNull(controller.getStartXmlToTlk().getOnAction());
        assertNotNull(controller.getButtonToSelectInputTlkFile().getOnAction());
        assertNotNull(controller.getButtonToSelectOutputXmlFile().getOnAction());
        assertNotNull(controller.getButtonToSelectInputXmlFile().getOnAction());
        assertNotNull(controller.getButtonToSelectOutputTlkFile().getOnAction());
    }

    @Test
    void createXmlTabConvertsTlkToXml() throws Exception {
        Path tlk = new TlkTestFile().male(1, "Привет").maleRaw(-2, -1).female(1, "Hi").writeTo(dir.resolve("in.tlk"));
        final Path xml = dir.resolve("out.xml");
        controller.getTextFieldInputPathTlkToXml().setText(tlk.toString());
        controller.getTextFieldOutputPathTlkToXml().setText(xml.toString());

        convertTlkToXml();

        assertEquals(FxmlController.STATUS_DONE, controller.getStatusTlkToXml().getText());
        assertEquals(1.0, controller.getProgressBarTlkToXml().getProgress(), 0.0);
        assertFalse(controller.getProgressBarTlkToXml().progressProperty().isBound());
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
        controller.getTextFieldInputPathTlkToXml().setText(tlk.toString());
        controller.getTextFieldOutputPathTlkToXml().setText(xml.toString());

        JavaFxToolkit.onFxThread(() -> {
            controller.getStartTlkToXml().fire();
            // the handler returns at once, with the tab locked until the conversion is over
            assertTrue(controller.getStartTlkToXml().isDisabled());
            assertTrue(controller.getButtonToSelectInputTlkFile().isDisabled());
            assertTrue(controller.getTextFieldOutputPathTlkToXml().isDisabled());
            assertTrue(controller.getChoiceBoxFormatTlkToXml().isDisabled());
            assertEquals(FxmlController.STATUS_RUNNING, controller.getStatusTlkToXml().getText());
            assertTrue(controller.getProgressBarTlkToXml().progressProperty().isBound());
            return null;
        });
        awaitIdle(controller.getStartTlkToXml(), controller.getStatusTlkToXml());

        assertEquals(FxmlController.STATUS_DONE, controller.getStatusTlkToXml().getText());
        assertFalse(controller.getButtonToSelectInputTlkFile().isDisabled());
        assertFalse(controller.getTextFieldOutputPathTlkToXml().isDisabled());
        assertEquals("fired", ExportedXml.parse(xml).strings.get(0).data);
    }

    @Test
    void createTlkTabConvertsXmlToTlk() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        Path tlk = dir.resolve("out.tlk");
        controller.getTextFieldInputPathXmlToTlk().setText(xml.toString());
        controller.getTextFieldOutputPathXmlToTlk().setText(tlk.toString());

        convertXmlToTlk();

        assertEquals(FxmlController.STATUS_DONE, controller.getStatusXmlToTlk().getText());
        assertEquals(1.0, controller.getProgressBarXmlToTlk().getProgress(), 0.0);
        TlkFile t = new TlkFile();
        t.loadTlkData(tlk.toString());
        assertEquals("Hello", TlkFileTest.refs(t).get(0).data);
        assertEquals("Female", TlkFileTest.refs(t).get(7).data);
    }

    @Test
    void testVersionCheckBoxAddsTheIdPrefix() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        Path tlk = dir.resolve("debug.tlk");
        controller.getTextFieldInputPathXmlToTlk().setText(xml.toString());
        controller.getTextFieldOutputPathXmlToTlk().setText(tlk.toString());
        controller.getCheckBoxDebugXmlToTlk().setSelected(true);

        convertXmlToTlk();

        assertEquals(FxmlController.STATUS_DONE, controller.getStatusXmlToTlk().getText());
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
        controller.getTextFieldInputPathTlkToXml().setText(missing.toString());
        controller.getTextFieldOutputPathTlkToXml().setText(dir.resolve("out.xml").toString());

        convertTlkToXml();

        assertEquals(FxmlController.STATUS_FAILED + "file not found: " + missing, controller.getStatusTlkToXml().getText());
        assertEquals(0.0, controller.getProgressBarTlkToXml().getProgress(), 0.0);
        assertFalse(controller.getStartTlkToXml().isDisabled());
    }

    @Test
    void invalidXmlIsReportedInTheStatus() throws Exception {
        Path xml = TestSupport.resource("/xml/old-version.xml");
        controller.getTextFieldInputPathXmlToTlk().setText(xml.toString());
        controller.getTextFieldOutputPathXmlToTlk().setText(dir.resolve("old.tlk").toString());

        convertXmlToTlk();

        String status = controller.getStatusXmlToTlk().getText();
        assertTrue(status.startsWith(FxmlController.STATUS_FAILED + "The XML file was written by TLK tool version 1.0.2"), status);
        assertFalse(Files.exists(dir.resolve("old.tlk")));
    }

    @Test
    void missingPathsAreReportedWithoutStarting() throws Exception {
        controller.getTextFieldOutputPathTlkToXml().setText(dir.resolve("out.xml").toString());
        JavaFxToolkit.onFxThread(() -> TestSupport.call(controller, "startExportTlkToXml", TestSupport.types()));
        assertEquals("Select an input file", controller.getStatusTlkToXml().getText());
        assertFalse(controller.getStartTlkToXml().isDisabled());

        controller.getTextFieldInputPathXmlToTlk().setText("  in.xml ");
        JavaFxToolkit.onFxThread(() -> TestSupport.call(controller, "startExportXmlToTlk", TestSupport.types()));
        assertEquals("Select an output file", controller.getStatusXmlToTlk().getText());
    }

    // ---------------------------------------------------------------- format selection

    @Test
    void formatChoiceBoxesOfferAllFormatsAndDefaultToXml() {
        for (javafx.scene.control.ChoiceBox<FileFormat> box : Arrays.asList(controller.getChoiceBoxFormatTlkToXml(),
                controller.getChoiceBoxFormatXmlToTlk())) {
            assertEquals(Arrays.asList(FileFormat.XML, FileFormat.TXT, FileFormat.CSV), box.getItems());
            assertSame(FileFormat.XML, box.getValue());
        }
    }

    @Test
    void outputFormatFollowsTheOutputFileName() {
        TextField output = controller.getTextFieldOutputPathTlkToXml();
        output.setText(dir.resolve("out.csv").toString());
        assertSame(FileFormat.CSV, controller.getChoiceBoxFormatTlkToXml().getValue());
        output.setText(dir.resolve("out.TXT").toString());
        assertSame(FileFormat.TXT, controller.getChoiceBoxFormatTlkToXml().getValue());
        output.setText(dir.resolve("out.tlk").toString());
        output.setText("");
        assertSame(FileFormat.TXT, controller.getChoiceBoxFormatTlkToXml().getValue());
        assertEquals("", output.getText());
    }

    @Test
    void choosingAnOutputFormatChangesTheOutputExtension() {
        TextField output = controller.getTextFieldOutputPathTlkToXml();
        output.setText(dir.resolve("out.xml") + " ");
        controller.getChoiceBoxFormatTlkToXml().setValue(FileFormat.CSV);
        assertEquals(dir.resolve("out.csv").toString(), output.getText());
        controller.getChoiceBoxFormatTlkToXml().setValue(FileFormat.TXT);
        assertEquals(dir.resolve("out.txt").toString(), output.getText());

        // a path without a format extension is left alone
        output.setText(dir.resolve("out.dat").toString());
        controller.getChoiceBoxFormatTlkToXml().setValue(FileFormat.XML);
        assertEquals(dir.resolve("out.dat").toString(), output.getText());
        output.setText("");
        controller.getChoiceBoxFormatTlkToXml().setValue(FileFormat.CSV);
        assertEquals("", output.getText());
    }

    @Test
    void inputFormatFollowsTheInputFileName() {
        TextField input = controller.getTextFieldInputPathXmlToTlk();
        input.setText("strings.csv");
        assertSame(FileFormat.CSV, controller.getChoiceBoxFormatXmlToTlk().getValue());
        input.setText("strings.txt");
        assertSame(FileFormat.TXT, controller.getChoiceBoxFormatXmlToTlk().getValue());
        input.setText("strings.dat");
        assertSame(FileFormat.TXT, controller.getChoiceBoxFormatXmlToTlk().getValue());
        // choosing the format by hand does not touch the input path
        controller.getChoiceBoxFormatXmlToTlk().setValue(FileFormat.XML);
        assertEquals("strings.dat", input.getText());
        // nor does it change the output format of the other tab
        assertSame(FileFormat.XML, controller.getChoiceBoxFormatTlkToXml().getValue());
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
            controller.getTextFieldInputPathTlkToXml().setText(tlk.toString());
            controller.getChoiceBoxFormatTlkToXml().setValue(format);
            controller.getTextFieldOutputPathTlkToXml().setText(text.toString());
            convertTlkToXml();
            assertEquals(FxmlController.STATUS_DONE, controller.getStatusTlkToXml().getText());
            assertTrue(TestSupport.readUtf8(text).contains(format == FileFormat.TXT ? "3: two\\nlines" : "3,2,true,\"two\nlines\""));

            Path back = dir.resolve("back-" + format + ".tlk");
            controller.getTextFieldInputPathXmlToTlk().setText(text.toString());
            assertSame(format, controller.getChoiceBoxFormatXmlToTlk().getValue());
            controller.getTextFieldOutputPathXmlToTlk().setText(back.toString());
            convertXmlToTlk();
            assertEquals(FxmlController.STATUS_DONE, controller.getStatusXmlToTlk().getText());
            TlkFile t = new TlkFile();
            t.loadTlkData(back.toString());
            assertEquals(Arrays.asList("Привет, \"мир\"", null, "two\nlines", "Hi"), TlkFileTest.texts(t));
        }
    }

    @Test
    void wrongInputFormatIsReportedInTheStatus() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        controller.getTextFieldInputPathXmlToTlk().setText(xml.toString());
        controller.getChoiceBoxFormatXmlToTlk().setValue(FileFormat.TXT);
        controller.getTextFieldOutputPathXmlToTlk().setText(dir.resolve("wrong.tlk").toString());

        convertXmlToTlk();

        String status = controller.getStatusXmlToTlk().getText();
        assertTrue(status.startsWith(FxmlController.STATUS_FAILED + "Missing header \"# TLKToolVersion=<version>\""), status);
        assertFalse(controller.getChoiceBoxFormatXmlToTlk().isDisabled());
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
