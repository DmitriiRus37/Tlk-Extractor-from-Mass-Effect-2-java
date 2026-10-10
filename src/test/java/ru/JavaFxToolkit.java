package ru;

import javafx.application.Platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Starts the JavaFX toolkit once per test JVM for the GUI tests, keeping them CI-safe.
 *
 * <p>If the JavaFX runtime has the headless Glass platform (JavaFX 25 and later) and no
 * {@code glass.platform} was chosen explicitly, it is selected together with the software
 * renderer, so no display is needed. Otherwise the normal platform is tried. If the toolkit
 * cannot start (no display, no native libraries, ...) the calling tests are skipped through
 * {@link #assumeAvailable()} instead of failing.
 *
 * <p>Uses only reflection for {@code Platform.startup} (JavaFX 9+) and its JavaFX 8 counterpart,
 * so it compiles against either.
 */
final class JavaFxToolkit {

    private static boolean attempted;
    private static boolean started;
    private static boolean headless;
    private static String problem = "not started";

    private JavaFxToolkit() {
    }

    /** Starts the toolkit if necessary; skips the calling test if it is not available. */
    static synchronized void assumeAvailable() {
        if (!attempted) {
            attempted = true;
            start();
        }
        assumeTrue(started, "JavaFX toolkit not available: " + problem);
    }

    /** Whether the toolkit runs on the headless Glass platform (windows are never shown). */
    static synchronized boolean isHeadless() {
        return started && headless;
    }

    private static void start() {
        String platform = System.getProperty("glass.platform");
        if (platform == null && headlessPlatformAvailable()) {
            platform = "Headless";
            System.setProperty("glass.platform", platform);
            if (System.getProperty("prism.order") == null) {
                System.setProperty("prism.order", "sw");
            }
        }
        headless = "headless".equalsIgnoreCase(platform);
        CountDownLatch latch = new CountDownLatch(1);
        try {
            startup(latch::countDown);
            if (!latch.await(30, TimeUnit.SECONDS)) {
                problem = "toolkit start timed out";
                return;
            }
            started = true;
        } catch (IllegalStateException e) {
            // already started by someone else in this JVM
            started = String.valueOf(e.getMessage()).contains("already");
            problem = e.toString();
        } catch (Throwable t) {
            problem = t.toString();
        }
        if (started) {
            Platform.setImplicitExit(false);
        }
    }

    private static void startup(Runnable onStarted) throws Throwable {
        Method startup;
        try {
            startup = Platform.class.getMethod("startup", Runnable.class);
        } catch (NoSuchMethodException javaFx8) {
            startup = Class.forName("com.sun.javafx.application.PlatformImpl").getMethod("startup", Runnable.class);
        }
        try {
            startup.invoke(null, onStarted);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static boolean headlessPlatformAvailable() {
        try {
            Class.forName("com.sun.glass.ui.headless.HeadlessApplication", false, JavaFxToolkit.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs {@code task} on the JavaFX application thread and waits for it; exceptions and
     * assertion failures thrown by the task are rethrown unwrapped.
     */
    static <T> T onFxThread(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<>(task);
        Platform.runLater(future);
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
