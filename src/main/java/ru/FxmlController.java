package ru;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;

/**
 * Controller of {@code app.fxml}: connects the "Create XML" tab to {@link TlkFile} and the
 * "Create Tlk" tab to {@link HuffmanCompression}.
 *
 * <p>Each tab has an input path, an output path (filled in from the input path with the other
 * extension until the user picks an output file explicitly), a START button, a progress bar and
 * a status field. Conversions run synchronously on the JavaFX application thread, so the window
 * freezes while one runs and the intermediate "in progress" status is never actually shown; the
 * progress bars are only ever reset to 0. Exceptions from a conversion go to the thread's
 * uncaught-exception handler and are not reported in the UI.
 *
 * <p>Lombok generates getters and setters for all fields; {@link App} uses {@code setApp}.
 */
@Getter @Setter
public class FxmlController {

    /** Not used; the input path is read from {@link #textFieldInputPathTlkToXml}. */
    public File inputTlkFile;
    /** Not used; the output path is read from {@link #textFieldIOutputPathTlkToXml}. */
    public File outputXmlFile;
    /**
     * The running application, set by {@link App#start(javafx.stage.Stage)} right after the FXML
     * is loaded; its stage is the owner of the input file choosers.
     */
    public App app;

    /** "Select" button for the TLK input file; its action is wired in the FXML. */
    @FXML
    private Button buttonToSelectInputTlkFile;
    /** "Select" button for the XML output file; its action is wired in the FXML. */
    @FXML
    private Button buttonToSelectOutputXmlFile;
    /** START button of the "Create XML" tab; its action is wired in the FXML. */
    @FXML
    private Button startTlkToXml;
    /** Path of the TLK file to convert, chosen with the dialog or typed in. */
    @FXML
    private TextField textFieldInputPathTlkToXml;
    /** Path of the XML file to write; defaults to the input path with a {@code .xml} extension. */
    @FXML
    private TextField textFieldIOutputPathTlkToXml;

    /** Read-only status field of the "Create XML" tab ("in progress", then "DONE"). */
    @FXML
    private TextField statusTlkToXml;
    /** Not injected: {@code app.fxml} has no element with this {@code fx:id}, so it stays {@code null}. */
    @FXML
    private TextField textFieldStatusTlkToXml;
    /**
     * Progress bar of the "Create XML" tab; reset to 0 on START and passed to
     * {@link TlkFile#storeToFile} as its progress callback, which is never called, so the bar
     * never advances.
     */
    @FXML
    public ProgressBar progressBarTlkToXml;
    /**
     * Whether the user picked the XML output file explicitly; once set, choosing a new input
     * file no longer overwrites the output path.
     */
    boolean textFieldOutputChosenTlkToXml = false;

    /**
     * Creates the controller. Called reflectively by {@link javafx.fxml.FXMLLoader}, which then
     * injects the {@code @FXML} fields; {@link #app} is set afterwards by {@link App}.
     */
    public FxmlController() {
    }

    /**
     * Handles the input "Select" button of the "Create XML" tab: lets the user pick a
     * {@code .tlk} file and puts its path into the input field. Unless an output file was
     * chosen explicitly, the output field is set to the same path with a {@code .xml}
     * extension. Does nothing if the dialog is cancelled.
     */
    @FXML
    private void selectInputTlkFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TLK files", "*.tlk"));
        File f = fc.showOpenDialog(app.getPrimaryStage());
        if (f != null) {
            textFieldInputPathTlkToXml.setText(f.getAbsolutePath());
            if (!textFieldOutputChosenTlkToXml) {
                textFieldIOutputPathTlkToXml.setText(FilenameUtils.removeExtension(f.getAbsolutePath())+".xml");
            }
        }
    }

    /**
     * Handles the output "Select" button of the "Create XML" tab: lets the user pick the XML
     * file to write and marks the output path as explicitly chosen. Does nothing if the dialog
     * is cancelled.
     *
     * <p>Note: this shows an <em>open</em> dialog, so it can only select an existing file, and
     * the dialog has no owner window.
     */
    @FXML
    private void selectOutputXmlFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        File f = fc.showOpenDialog(null);
        if (f!=null) {
            textFieldIOutputPathTlkToXml.setText(f.getAbsolutePath());
            textFieldOutputChosenTlkToXml = true;
        }
    }

    /**
     * Handles START on the "Create XML" tab: loads the TLK file named in the input field (as a
     * PC, little-endian file) and writes it as XML to the path in the output field.
     *
     * @throws IOException if the TLK file cannot be read or the XML file cannot be written
     */
    @FXML
    private void startExportTlkToXml() throws IOException {
        statusTlkToXml.setText("in progress");
        progressBarTlkToXml.setProgress(0.0);
        TlkFile tf = new TlkFile();
        tf.loadTlkData(textFieldInputPathTlkToXml.getText());
        tf.storeToFile(textFieldIOutputPathTlkToXml.getText(), FileFormat.XML, progressBarTlkToXml::setProgress);
        statusTlkToXml.setText("DONE");
    }

    /** Not used; the input path is read from {@link #textFieldInputPathXmlToTlk}. */
    public File inputXmlFile;
    /** Not used; the output path is read from {@link #textFieldIOutputPathXmlToTlk}. */
    public File outputTlkFile;

    /** "Select" button for the XML input file; its action is wired in the FXML. */
    @FXML
    private Button buttonToSelectInputXmlFile;
    /** "Select" button for the TLK output file; its action is wired in the FXML. */
    @FXML
    private Button buttonToSelectOutputTlkFile;
    /** START button of the "Create Tlk" tab; its action is wired in the FXML. */
    @FXML
    private Button startXmlToTlk;
    /** Path of the XML file to convert, chosen with the dialog or typed in. */
    @FXML
    private TextField textFieldInputPathXmlToTlk;
    /** Path of the TLK file to write; defaults to the input path with a {@code .tlk} extension. */
    @FXML
    private TextField textFieldIOutputPathXmlToTlk;
    /** Not injected: {@code app.fxml} has no element with this {@code fx:id}, so it stays {@code null}. */
    @FXML
    private TextField textFieldStatusXmlToTlk;

    /** Read-only status field of the "Create Tlk" tab ("in progress", then "DONE"). */
    @FXML
    private TextField statusXmlToTlk;
    /** Progress bar of the "Create Tlk" tab; reset to 0 on START, never advanced. */
    @FXML
    public ProgressBar progressBarXmlToTlk;
    /**
     * Whether the user picked the TLK output file explicitly; once set, choosing a new input
     * file no longer overwrites the output path.
     */
    boolean textFieldOutputChosenXmlToTlk = false;

    /**
     * Handles the input "Select" button of the "Create Tlk" tab: lets the user pick an
     * {@code .xml} file and puts its path into the input field. Unless an output file was chosen
     * explicitly, the output field is set to the same path with a {@code .tlk} extension.
     *
     * <p>Note: unlike {@link #selectInputTlkFile()} there is no {@code null} check, so
     * cancelling the dialog throws a {@link NullPointerException}.
     */
    @FXML
    private void selectInputXmlFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        File f = fc.showOpenDialog(app.getPrimaryStage());
        textFieldInputPathXmlToTlk.setText(f.getAbsolutePath());
        if (!textFieldOutputChosenXmlToTlk) {
            textFieldIOutputPathXmlToTlk.setText(FilenameUtils.removeExtension(f.getAbsolutePath())+".tlk");
        }
    }

    /**
     * Handles the output "Select" button of the "Create Tlk" tab: lets the user pick the TLK file
     * to write and marks the output path as explicitly chosen.
     *
     * <p>Note: this shows an <em>open</em> dialog without an owner window, so it can only select
     * an existing file, and there is no {@code null} check, so cancelling the dialog throws a
     * {@link NullPointerException}.
     */
    @FXML
    private void selectOutputTlkFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TLK files", "*.tlk"));
        File f = fc.showOpenDialog(null);
        textFieldIOutputPathXmlToTlk.setText(f.getAbsolutePath());
        textFieldOutputChosenXmlToTlk = true;
    }

    /**
     * Handles START on the "Create Tlk" tab: loads the XML file named in the input field and
     * writes a TLK file to the path in the output field.
     *
     * <p>Note: debug mode is hard-coded on, so every localizable string in the generated file is
     * prefixed with {@code "(#<id>) "} (see
     * {@link HuffmanCompression#loadInputData(String, FileFormat, boolean)}).
     *
     * @throws IOException if the XML file cannot be read or is invalid, or the TLK file cannot be
     *                     written
     */
    @FXML
    private void startExportXmlToTlk() throws IOException {
        statusXmlToTlk.setText("in progress");
        progressBarXmlToTlk.setProgress(0.0);
        HuffmanCompression hc = new HuffmanCompression();
        hc.loadInputData(textFieldInputPathXmlToTlk.getText(), FileFormat.XML, true);
        hc.saveToTlkFile(textFieldIOutputPathXmlToTlk.getText(), true);
        statusXmlToTlk.setText("DONE");
    }

}
