package ru;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.Getter;

import java.io.IOException;

/**
 * JavaFX entry point of the TLK extractor.
 *
 * <p>Loads the single window from {@code /app.fxml}, hands itself to the {@link FxmlController}
 * (which needs the stage as the owner of its file choosers) and shows the window as
 * non-resizable. All conversion work is started from the controller's event handlers.
 */
public class App extends Application {

    /**
     * The main window, stored by {@link #start(Stage)}; {@code null} before that. Exposed through
     * a Lombok-generated {@code getPrimaryStage()} so the controller can parent dialogs to it.
     */
    @Getter
    private Stage primaryStage;

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
        this.primaryStage = primaryStage;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/app.fxml"));
        Parent root = loader.load();

        FxmlController controller = loader.getController();
        controller.setApp(this);

        primaryStage.setScene(new Scene(root));
        primaryStage.show();
        primaryStage.setResizable(false);
    }

    /**
     * Launches the JavaFX application, which instantiates this class and calls
     * {@link #start(Stage)}; returns only after the window has been closed.
     *
     * @param args command-line arguments; currently not passed on to JavaFX
     */
    public static void main(String[] args) {
        Application.launch();
    }

}
