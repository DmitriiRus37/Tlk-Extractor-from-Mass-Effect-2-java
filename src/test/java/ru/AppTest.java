package ru;

import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Starts the application window. Runs only on the headless Glass platform (see
 * {@link JavaFxToolkit}), so that no real window pops up, unless {@code -Dtlk.test.showWindows=true}.
 */
class AppTest {

    @Test
    void primaryStageIsUnsetBeforeStart() {
        assertNull(new App().getPrimaryStage());
    }

    @Test
    void startShowsTheMainWindow() throws Exception {
        JavaFxToolkit.assumeAvailable();
        assumeTrue(JavaFxToolkit.isHeadless() || Boolean.getBoolean("tlk.test.showWindows"),
                "not on the headless platform; would open a real window");

        JavaFxToolkit.onFxThread(() -> {
            Stage stage = new Stage();
            App app = new App();
            app.start(stage);
            try {
                assertSame(stage, app.getPrimaryStage());
                assertTrue(stage.isShowing());
                assertFalse(stage.isResizable());

                Scene scene = stage.getScene();
                assertNotNull(scene);
                assertNotNull(scene.lookup("#textFieldInputPathTlkToXml"));
                assertNotNull(scene.lookup("#startXmlToTlk"));

                TabPane tabs = (TabPane) scene.lookup(".tab-pane");
                List<String> titles = new ArrayList<>();
                for (Tab tab : tabs.getTabs()) {
                    titles.add(tab.getText());
                }
                assertEquals(Arrays.asList("Create XML", "Create Tlk", "About"), titles);
            } finally {
                stage.hide();
            }
            return null;
        });
    }
}
