package ru;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * JavaFX entry point of the TLK extractor.
 *
 * <p>Loads the single window from {@code /app.fxml} and shows it as non-resizable. All
 * conversion work is started from the event handlers of its {@link FxmlController}.
 */
public class App extends Application {

    /** Title of the main window. */
    static final String TITLE = "Mass Effect 2 TLK Extractor";

    /**
     * Creates the application. Instantiated reflectively by the JavaFX launcher, not by
     * application code.
     */
    public App() {
    }

    /**
     * Builds and shows the main window. Called by the JavaFX runtime on the JavaFX application
     * thread once the toolkit is initialised.
     *
     * @param primaryStage the window supplied by the runtime
     * @throws IOException if {@code /app.fxml} cannot be loaded
     */
    @Override
    public void start(Stage primaryStage) throws IOException {
        Parent root = FXMLLoader.load(getClass().getResource("/app.fxml"));
        primaryStage.setTitle(TITLE);
        primaryStage.setScene(new Scene(root));
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    /**
     * Launches the JavaFX application, which instantiates this class and calls
     * {@link #start(Stage)}; returns only after the window has been closed.
     *
     * @param args command-line arguments, passed on to JavaFX (see
     *             {@link Application#getParameters()})
     */
    public static void main(String[] args) {
        launch(args);
    }

}
