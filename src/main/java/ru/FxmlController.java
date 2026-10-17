package ru;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Controller of {@code app.fxml}: connects the "Create XML/TXT/CSV" tab to {@link TlkFile} and the
 * "Create Tlk" tab to {@link HuffmanCompression}.
 *
 * <p>Each tab has an input path, an output path (filled in from the input path with the other
 * extension until the user picks an output file explicitly), a {@link FileFormat} choice box
 * (the output format of the first tab, the input format of the second; {@link FileFormat#XML} by
 * default), a START button, a progress bar and a status field; the "Create Tlk" tab also has the
 * "test version" check box of the C# original. The format follows the file name: picking or
 * typing a path ending in {@code .xml}, {@code .txt} or {@code .csv} selects that format, and
 * choosing another output format changes the extension of the output path accordingly.
 * A conversion runs on a background thread: while it runs, the tab's buttons and fields are
 * disabled, the status says {@value #STATUS_RUNNING} and the progress bar first spins (while the
 * input is read) and then fills up (while the output is written). Afterwards the status says
 * {@value #STATUS_DONE} or {@value #STATUS_FAILED} followed by the reason.
 *
 * <p>The {@code @FXML} controls and the "output chosen" flags are package-private so that the
 * tests can reach them.
 */
public class FxmlController {

    /** Status text while a conversion runs. */
    static final String STATUS_RUNNING = "in progress";
    /** Status text after a successful conversion. */
    static final String STATUS_DONE = "DONE";
    /** Start of the status text after a failed conversion; the reason follows. */
    static final String STATUS_FAILED = "FAILED: ";
    /** Formats offered by the choice boxes, in display order. */
    static final List<FileFormat> FORMATS = Collections.unmodifiableList(
            Arrays.asList(FileFormat.XML, FileFormat.TXT, FileFormat.CSV));
    /** Extension filters of the file dialogs for TLK files. */
    private static final List<FileChooser.ExtensionFilter> TLK_FILTERS =
            Collections.singletonList(new FileChooser.ExtensionFilter("TLK files", "*.tlk"));

    /** "Select" button for the TLK input file; its action is wired in the FXML. */
    @FXML
    Button buttonToSelectInputTlkFile;
    /** "Select" button for the output file (XML, TXT or CSV); its action is wired in the FXML. */
    @FXML
    Button buttonToSelectOutputXmlFile;
    /** START button of the "Create XML" tab; its action is wired in the FXML. */
    @FXML
    Button startTlkToXml;
    /** Path of the TLK file to convert, chosen with the dialog or typed in. */
    @FXML
    TextField textFieldInputPathTlkToXml;
    /**
     * Path of the file to write; defaults to the input path with the extension of the selected
     * output format.
     */
    @FXML
    TextField textFieldOutputPathTlkToXml;
    /** Output format of the "Create XML/TXT/CSV" tab; {@link FileFormat#XML} by default. */
    @FXML
    ChoiceBox<FileFormat> choiceBoxFormatTlkToXml;
    /** Read-only status field of the "Create XML" tab. */
    @FXML
    TextField statusTlkToXml;
    /** Progress bar of the "Create XML" tab. */
    @FXML
    ProgressBar progressBarTlkToXml;
    /**
     * Whether the user picked the output file explicitly; once set, choosing a new input
     * file no longer overwrites the output path.
     */
    boolean textFieldOutputChosenTlkToXml;

    /** "Select" button for the input file (XML, TXT or CSV); its action is wired in the FXML. */
    @FXML
    Button buttonToSelectInputXmlFile;
    /** "Select" button for the TLK output file; its action is wired in the FXML. */
    @FXML
    Button buttonToSelectOutputTlkFile;
    /** START button of the "Create Tlk" tab; its action is wired in the FXML. */
    @FXML
    Button startXmlToTlk;
    /** Path of the XML, TXT or CSV file to convert, chosen with the dialog or typed in. */
    @FXML
    TextField textFieldInputPathXmlToTlk;
    /** Input format of the "Create Tlk" tab; {@link FileFormat#XML} by default. */
    @FXML
    ChoiceBox<FileFormat> choiceBoxFormatXmlToTlk;
    /** Path of the TLK file to write; defaults to the input path with a {@code .tlk} extension. */
    @FXML
    TextField textFieldOutputPathXmlToTlk;
    /**
     * "Test version" check box: if selected, every localizable string of the new TLK file is
     * prefixed with its id (see {@link HuffmanCompression#loadInputData(String, FileFormat, boolean)}).
     */
    @FXML
    CheckBox checkBoxDebugXmlToTlk;
    /** Read-only status field of the "Create Tlk" tab. */
    @FXML
    TextField statusXmlToTlk;
    /** Progress bar of the "Create Tlk" tab. */
    @FXML
    ProgressBar progressBarXmlToTlk;
    /**
     * Whether the user picked the TLK output file explicitly; once set, choosing a new input
     * file no longer overwrites the output path.
     */
    boolean textFieldOutputChosenXmlToTlk;

    /** The "About" tab. */
    @FXML
    private Tab aboutTab;
    /** The content of the "About" tab; empty until the tab is first shown. */
    @FXML
    private AnchorPane aboutPane;
    /** The text of the "About" tab, created by {@code app.fxml} but not yet in the scene. */
    @FXML
    private TextArea aboutText;

    /**
     * Creates the controller. Called reflectively by {@link javafx.fxml.FXMLLoader}, which then
     * injects the {@code @FXML} fields.
     */
    public FxmlController() {
    }

    /**
     * Called by {@link javafx.fxml.FXMLLoader} once the {@code @FXML} fields are injected: fills
     * both format choice boxes with {@link #FORMATS}, selects {@link FileFormat#XML} and makes the
     * formats follow the file names (see the class description).
     */
    @FXML
    private void initialize() {
        for (ChoiceBox<FileFormat> box : Arrays.asList(choiceBoxFormatTlkToXml, choiceBoxFormatXmlToTlk)) {
            box.getItems().setAll(FORMATS);
            box.setValue(FileFormat.XML);
        }
        followFileName(textFieldOutputPathTlkToXml, choiceBoxFormatTlkToXml);
        followFileName(textFieldInputPathXmlToTlk, choiceBoxFormatXmlToTlk);
        // a text area in the scene costs its skin and CSS at startup even in a hidden tab
        aboutTab.setOnSelectionChanged(event -> {
            if (aboutTab.isSelected() && aboutPane.getChildren().isEmpty()) {
                aboutPane.getChildren().add(aboutText);
            }
        });
        choiceBoxFormatTlkToXml.valueProperty().addListener((observable, oldFormat, newFormat) -> {
            String path = textFieldOutputPathTlkToXml.getText();
            FileFormat current = FileFormat.fromFileName(path);
            if (newFormat != null && current != null && current != newFormat) {
                textFieldOutputPathTlkToXml.setText(withExtension(new File(path.trim()), newFormat.getExtension()));
            }
        });
    }

    /**
     * Makes a choice box select the format whose extension the path in a text field has,
     * whenever the path changes; paths with another extension leave the choice alone.
     *
     * @param field  the path field
     * @param format the choice box
     */
    private static void followFileName(TextField field, ChoiceBox<FileFormat> format) {
        field.textProperty().addListener((observable, oldPath, newPath) -> {
            FileFormat inferred = FileFormat.fromFileName(newPath);
            if (inferred != null) {
                format.setValue(inferred);
            }
        });
    }

    /**
     * Work done by one conversion on the background thread.
     */
    @FunctionalInterface
    interface Conversion {
        /**
         * Runs the conversion.
         *
         * @param progress receives the completed fraction (0 to 1) of writing the output
         * @throws Exception if the conversion fails; its message is shown in the status field
         */
        void run(DoubleConsumer progress) throws Exception;
    }

    /**
     * Handles the input "Select" button of the "Create XML/TXT/CSV" tab: lets the user pick a
     * {@code .tlk} file and puts its path into the input field. Unless an output file was
     * chosen explicitly, the output field is set to the same path with the extension of the
     * selected output format. Does nothing if the dialog is cancelled.
     */
    @FXML
    private void selectInputTlkFile() {
        File f = chooseFile(true, textFieldInputPathTlkToXml, TLK_FILTERS);
        if (f != null) {
            textFieldInputPathTlkToXml.setText(f.getAbsolutePath());
            if (!textFieldOutputChosenTlkToXml) {
                String extension = choiceBoxFormatTlkToXml.getValue().getExtension();
                textFieldOutputPathTlkToXml.setText(withExtension(f, extension));
            }
        }
    }

    /**
     * Handles the output "Select" button of the "Create XML/TXT/CSV" tab: lets the user choose
     * where to save the file (offering the selected output format's extension first) and marks
     * the output path as explicitly chosen; a file name with another format's extension selects
     * that format. Does nothing if the dialog is cancelled.
     */
    @FXML
    private void selectOutputXmlFile() {
        File f = chooseFile(false, textFieldOutputPathTlkToXml,
                formatFilters(choiceBoxFormatTlkToXml.getValue(), false));
        if (f != null) {
            textFieldOutputPathTlkToXml.setText(f.getAbsolutePath());
            textFieldOutputChosenTlkToXml = true;
        }
    }

    /**
     * Handles the input "Select" button of the "Create Tlk" tab: lets the user pick an
     * {@code .xml}, {@code .txt} or {@code .csv} file and puts its path into the input field,
     * which selects the matching input format. Unless an output file was chosen explicitly, the
     * output field is set to the same path with a {@code .tlk} extension. Does nothing if the
     * dialog is cancelled.
     */
    @FXML
    private void selectInputXmlFile() {
        File f = chooseFile(true, textFieldInputPathXmlToTlk,
                formatFilters(choiceBoxFormatXmlToTlk.getValue(), true));
        if (f != null) {
            textFieldInputPathXmlToTlk.setText(f.getAbsolutePath());
            if (!textFieldOutputChosenXmlToTlk) {
                textFieldOutputPathXmlToTlk.setText(withExtension(f, "tlk"));
            }
        }
    }

    /**
     * Handles the output "Select" button of the "Create Tlk" tab: lets the user choose where to
     * save the TLK file and marks the output path as explicitly chosen. Does nothing if the
     * dialog is cancelled.
     */
    @FXML
    private void selectOutputTlkFile() {
        File f = chooseFile(false, textFieldOutputPathXmlToTlk, TLK_FILTERS);
        if (f != null) {
            textFieldOutputPathXmlToTlk.setText(f.getAbsolutePath());
            textFieldOutputChosenXmlToTlk = true;
        }
    }

    /**
     * Returns the extension filters of a file dialog for the text formats: one per format, the
     * given one first, preceded for an open dialog by one for all supported files.
     *
     * @param selected the format to offer first
     * @param open     whether the filters are for an open dialog
     * @return the filters, the first one to be preselected
     */
    static List<FileChooser.ExtensionFilter> formatFilters(FileFormat selected, boolean open) {
        List<FileChooser.ExtensionFilter> filters = new ArrayList<>();
        List<String> all = new ArrayList<>();
        List<FileFormat> order = new ArrayList<>(FORMATS);
        order.remove(selected);
        order.add(0, selected);
        for (FileFormat format : order) {
            String pattern = "*." + format.getExtension();
            all.add(pattern);
            filters.add(new FileChooser.ExtensionFilter(format + " files", pattern));
        }
        if (open) {
            filters.add(0, new FileChooser.ExtensionFilter("XML, TXT and CSV files", all));
        }
        return filters;
    }

    /**
     * Shows a file dialog owned by this controller's window, starting in the directory of the
     * path currently in {@code field} (and, for a save dialog, with its file name).
     *
     * @param open    {@code true} for an open dialog, {@code false} for a save dialog
     * @param field   field holding the current path, possibly empty
     * @param filters extension filters; the first one is preselected
     * @return the chosen file, or {@code null} if the dialog was cancelled
     */
    private static File chooseFile(boolean open, TextField field, List<FileChooser.ExtensionFilter> filters) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().addAll(filters);
        chooser.setSelectedExtensionFilter(filters.get(0));
        String current = field.getText();
        if (current != null && !current.trim().isEmpty()) {
            File file = new File(current.trim()).getAbsoluteFile();
            File directory = file.getParentFile();
            if (directory != null && directory.isDirectory()) {
                chooser.setInitialDirectory(directory);
            }
            if (!open) {
                chooser.setInitialFileName(file.getName());
            }
        }
        Window owner = field.getScene() == null ? null : field.getScene().getWindow();
        return open ? chooser.showOpenDialog(owner) : chooser.showSaveDialog(owner);
    }

    /**
     * Returns the absolute path of a file with its extension replaced.
     *
     * @param file      the file
     * @param extension new extension, without the dot
     * @return the path ending in {@code "." + extension}
     */
    private static String withExtension(File file, String extension) {
        String path = file.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        // an extension is a dot in the file name, not in a directory name
        boolean hasExtension = dot > Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (hasExtension ? path.substring(0, dot) : path) + "." + extension;
    }

    /**
     * Handles START on the "Create XML/TXT/CSV" tab: converts the TLK file named in the input
     * field to a file of the selected format at the path in the output field, on a background
     * thread.
     */
    @FXML
    private void startExportTlkToXml() {
        final String input = textFieldInputPathTlkToXml.getText().trim();
        final String output = textFieldOutputPathTlkToXml.getText().trim();
        final FileFormat format = choiceBoxFormatTlkToXml.getValue();
        runInBackground(input, output, progress -> {
            TlkFile tlk = new TlkFile();
            tlk.loadTlkData(input);
            tlk.storeToFile(output, format, progress);
        }, progressBarTlkToXml, statusTlkToXml, startTlkToXml, buttonToSelectInputTlkFile,
                buttonToSelectOutputXmlFile, textFieldInputPathTlkToXml, textFieldOutputPathTlkToXml,
                choiceBoxFormatTlkToXml);
    }

    /**
     * Handles START on the "Create Tlk" tab: converts the file of the selected input format named
     * in the input field to a TLK file at the path in the output field, on a background thread.
     * If the "test version" box is checked, the strings get their id as a prefix.
     */
    @FXML
    private void startExportXmlToTlk() {
        final String input = textFieldInputPathXmlToTlk.getText().trim();
        final String output = textFieldOutputPathXmlToTlk.getText().trim();
        final FileFormat format = choiceBoxFormatXmlToTlk.getValue();
        final boolean debugVersion = checkBoxDebugXmlToTlk.isSelected();
        runInBackground(input, output, progress -> {
            HuffmanCompression hc = new HuffmanCompression();
            hc.loadInputData(input, format, debugVersion);
            hc.saveToTlkFile(output, progress);
        }, progressBarXmlToTlk, statusXmlToTlk, startXmlToTlk, buttonToSelectInputXmlFile,
                buttonToSelectOutputTlkFile, textFieldInputPathXmlToTlk, textFieldOutputPathXmlToTlk,
                choiceBoxFormatXmlToTlk, checkBoxDebugXmlToTlk);
    }

    /**
     * Runs a conversion on a new daemon thread, reporting its progress and outcome in the tab.
     * Must be called on the JavaFX application thread. If a path is missing, the status asks for
     * it and nothing is started.
     *
     * @param input      input path
     * @param output     output path
     * @param conversion the work to do
     * @param bar        progress bar of the tab: indeterminate until the conversion reports
     *                   progress, full when it succeeds, empty when it fails
     * @param status     status field of the tab
     * @param controls   controls to disable while the conversion runs
     */
    private static void runInBackground(String input, String output, Conversion conversion,
                                        ProgressBar bar, TextField status, Node... controls) {
        if (input.isEmpty() || output.isEmpty()) {
            status.setText(input.isEmpty() ? "Select an input file" : "Select an output file");
            return;
        }
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(-1, 1);
                conversion.run(fraction -> updateProgress(fraction, 1));
                return null;
            }
        };
        task.setOnSucceeded(event -> finish(bar, 1.0, status, STATUS_DONE, controls));
        task.setOnFailed(event -> finish(bar, 0.0, status, STATUS_FAILED + describe(task.getException()), controls));

        status.setText(STATUS_RUNNING);
        bar.progressProperty().bind(task.progressProperty());
        setDisabled(true, controls);
        Thread thread = new Thread(task, "tlk-conversion");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Shows the outcome of a conversion and re-enables the tab.
     *
     * @param bar        progress bar of the tab
     * @param progress   final progress to show
     * @param status     status field of the tab
     * @param statusText final status
     * @param controls   controls to enable again
     */
    private static void finish(ProgressBar bar, double progress, TextField status, String statusText,
                               Node... controls) {
        bar.progressProperty().unbind();
        bar.setProgress(progress);
        status.setText(statusText);
        setDisabled(false, controls);
    }

    /**
     * Disables or enables controls.
     *
     * @param disabled whether to disable them
     * @param controls the controls
     */
    private static void setDisabled(boolean disabled, Node... controls) {
        for (Node control : controls) {
            control.setDisable(disabled);
        }
    }

    /**
     * Turns the exception of a failed conversion into a short message for the status field.
     *
     * @param error the exception
     * @return a description of what went wrong
     */
    static String describe(Throwable error) {
        return switch (error) {
            case NoSuchFileException e -> "file not found: " + e.getMessage();
            case AccessDeniedException e -> "access denied: " + e.getMessage();
            default -> {
                String message = error.getMessage();
                yield message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
            }
        };
    }
}
