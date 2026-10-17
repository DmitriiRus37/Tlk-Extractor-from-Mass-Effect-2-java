package ru;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * Training run for the AOT cache that {@code run.sh} records on JDK 25+ (JEP 483/514/515): does
 * what a typical session does, without showing a window, so that the cache holds the classes and
 * method profiles of both the GUI startup and the conversions.
 *
 * <p>On {@code BIOGame_RUS.tlk} (JDK 25) a cold load/export/import round of all formats took
 * ~835 ms without a cache, ~990 ms with a cache recorded while the window was only opened and
 * closed (it made conversions slower) and ~610 ms with a cache from this run; JavaFX startup
 * went from ~580 to ~300 ms with either cache.
 */
public final class AotTraining {

    /** Not instantiable. */
    private AotTraining() {
    }

    /**
     * Starts JavaFX and builds the main window's scene without showing it, then converts a TLK
     * file to every text format and each of them back to TLK in a temporary directory, and exits.
     *
     * @param args the path of a TLK file to convert; without it only the GUI part runs
     * @throws Exception if anything fails; the caller then just has no cache
     */
    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> gui = new CompletableFuture<>();
        Platform.startup(() -> {
            try {
                Parent root = FXMLLoader.load(App.class.getResource("/app.fxml"));
                new Scene(root);
                root.applyCss();
                root.layout();
                gui.complete(null);
            } catch (IOException | RuntimeException e) {
                gui.completeExceptionally(e);
            }
        });
        try {
            gui.get();
            if (args.length > 0) {
                convert(Paths.get(args[0]));
            }
        } finally {
            Platform.exit();
        }
    }

    /**
     * Converts a TLK file through every format in a temporary directory, which is deleted
     * afterwards.
     *
     * @param tlk the TLK file
     * @throws IOException if a conversion fails
     */
    private static void convert(Path tlk) throws IOException {
        Path dir = Files.createTempDirectory("tlk-aot-training");
        try {
            WarmUp.convert(tlk, dir);
        } finally {
            WarmUp.delete(dir);
        }
    }
}
