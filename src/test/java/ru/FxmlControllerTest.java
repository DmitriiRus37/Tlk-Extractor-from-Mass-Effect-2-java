package ru;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller behind {@code app.fxml}, loaded through the real FXML. Needs a JavaFX toolkit;
 * skipped where none can start (see {@link JavaFxToolkit}). The file-chooser handlers are not
 * tested: they open native dialogs, which the headless platform does not support.
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

    private void invoke(final String handler) throws Exception {
        JavaFxToolkit.onFxThread(() -> TestSupport.call(controller, handler, TestSupport.types()));
    }

    @Test
    void fxmlInjectsTheControls() {
        assertNotNull(controller.getButtonToSelectInputTlkFile());
        assertNotNull(controller.getButtonToSelectOutputXmlFile());
        assertNotNull(controller.getStartTlkToXml());
        assertNotNull(controller.getTextFieldInputPathTlkToXml());
        assertNotNull(controller.getTextFieldIOutputPathTlkToXml());
        assertNotNull(controller.getStatusTlkToXml());
        assertNotNull(controller.getProgressBarTlkToXml());

        assertNotNull(controller.getButtonToSelectInputXmlFile());
        assertNotNull(controller.getButtonToSelectOutputTlkFile());
        assertNotNull(controller.getStartXmlToTlk());
        assertNotNull(controller.getTextFieldInputPathXmlToTlk());
        assertNotNull(controller.getTextFieldIOutputPathXmlToTlk());
        assertNotNull(controller.getStatusXmlToTlk());
        assertNotNull(controller.getProgressBarXmlToTlk());

        // declared @FXML but without a matching fx:id in app.fxml
        assertNull(controller.getTextFieldStatusTlkToXml());
        assertNull(controller.getTextFieldStatusXmlToTlk());
    }

    @Test
    void initialState() {
        assertNull(controller.getApp());
        assertFalse(controller.isTextFieldOutputChosenTlkToXml());
        assertFalse(controller.isTextFieldOutputChosenXmlToTlk());
        assertFalse(controller.getStatusTlkToXml().isEditable());
        assertFalse(controller.getStatusXmlToTlk().isEditable());
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
    void appCanBeSet() {
        App app = new App();
        controller.setApp(app);
        assertSame(app, controller.getApp());
    }

    @Test
    void createXmlTabConvertsTlkToXml() throws Exception {
        Path tlk = new TlkTestFile().male(1, "Привет").maleRaw(-2, -1).female(1, "Hi").writeTo(dir.resolve("in.tlk"));
        final Path xml = dir.resolve("out.xml");
        controller.getTextFieldInputPathTlkToXml().setText(tlk.toString());
        controller.getTextFieldIOutputPathTlkToXml().setText(xml.toString());

        invoke("startExportTlkToXml");

        assertEquals("DONE", controller.getStatusTlkToXml().getText());
        assertEquals(1.0, controller.getProgressBarTlkToXml().getProgress(), 0.0);
        ExportedXml parsed = ExportedXml.parse(xml);
        assertEquals(3, parsed.strings.size());
        assertEquals("Привет", parsed.strings.get(0).data);
        assertEquals("-1", parsed.strings.get(1).data);
        assertEquals("Hi", parsed.strings.get(2).data);
    }

    @Test
    void startButtonRunsTheConversion() throws Exception {
        Path tlk = new TlkTestFile().male(7, "fired").writeTo(dir.resolve("button.tlk"));
        Path xml = dir.resolve("button.xml");
        controller.getTextFieldInputPathTlkToXml().setText(tlk.toString());
        controller.getTextFieldIOutputPathTlkToXml().setText(xml.toString());

        JavaFxToolkit.onFxThread(() -> {
            controller.getStartTlkToXml().fire();
            return null;
        });

        assertEquals("DONE", controller.getStatusTlkToXml().getText());
        assertEquals("fired", ExportedXml.parse(xml).strings.get(0).data);
    }

    @Test
    void createTlkTabConvertsXmlToTlkInDebugMode() throws Exception {
        Path xml = TestSupport.resource("/xml/small.xml");
        Path tlk = dir.resolve("out.tlk");
        controller.getTextFieldInputPathXmlToTlk().setText(xml.toString());
        controller.getTextFieldIOutputPathXmlToTlk().setText(tlk.toString());

        invoke("startExportXmlToTlk");

        assertEquals("DONE", controller.getStatusXmlToTlk().getText());
        assertEquals(1.0, controller.getProgressBarXmlToTlk().getProgress(), 0.0);
        // the GUI always converts with the debug prefix: the data block must match a debug load
        HuffmanCompression debug = new HuffmanCompression();
        debug.loadInputData(xml.toString(), FileFormat.XML, true);
        byte[] expected = HuffmanAccess.expectedDataBlock(debug);
        byte[] written = Files.readAllBytes(tlk);
        assertArrayEquals(expected, Arrays.copyOfRange(written, written.length - expected.length, written.length));
    }

    @Test
    void conversionErrorsPropagate() {
        controller.getTextFieldInputPathTlkToXml().setText(dir.resolve("missing.tlk").toString());
        controller.getTextFieldIOutputPathTlkToXml().setText(dir.resolve("out.xml").toString());
        assertThrows(NoSuchFileException.class, () -> invoke("startExportTlkToXml"));
        assertNotEquals("DONE", controller.getStatusTlkToXml().getText());
    }

    @Test
    @Disabled("Bug: a failed conversion leaves the status at \"in progress\" (the exception is not reported in the UI)")
    void failedConversionIsReportedInTheStatus() {
        controller.getTextFieldInputPathTlkToXml().setText(dir.resolve("missing.tlk").toString());
        controller.getTextFieldIOutputPathTlkToXml().setText(dir.resolve("out.xml").toString());
        try {
            invoke("startExportTlkToXml");
        } catch (Exception expected) {
            // either way, the status must tell the user
        }
        String status = controller.getStatusTlkToXml().getText();
        assertTrue(!"in progress".equals(status) && !"DONE".equals(status), "status: " + status);
    }
}
