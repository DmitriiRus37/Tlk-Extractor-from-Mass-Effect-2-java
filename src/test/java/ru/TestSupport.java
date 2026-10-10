package ru;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Small helpers shared by the tests: reflective access to private state of the classes under
 * test (so production code does not have to be opened up for testing), file reading and the
 * location of the bundled sample file.
 *
 * <p>Only Java 8 APIs are used, since the test suite must also build on JDK 1.8.
 */
final class TestSupport {

    private TestSupport() {
    }

    /** The real Mass Effect 2 string table shipped in {@code files/}. */
    static Path samplePath() {
        String baseDir = System.getProperty("basedir", System.getProperty("user.dir"));
        return Paths.get(baseDir, "files", "BIOGame_RUS.tlk");
    }

    /** Absolute path of a test resource on the class path. */
    static Path resource(String name) {
        URL url = TestSupport.class.getResource(name);
        assertNotNull(url, "missing test resource " + name);
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    static Path writeUtf8(Path file, String content) throws IOException {
        return Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Reads a (possibly private, possibly inherited) field. */
    @SuppressWarnings("unchecked")
    static <T> T field(Object target, String name) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new IllegalArgumentException("no field " + name + " in " + type);
    }

    /**
     * Invokes a (possibly private) method; exceptions thrown by the method are rethrown
     * unwrapped, so tests can use {@code assertThrows} on them.
     */
    @SuppressWarnings("unchecked")
    static <T> T call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return (T) invoke(target.getClass(), target, name, types, args);
    }

    /** Static variant of {@link #call(Object, String, Class[], Object...)}. */
    @SuppressWarnings("unchecked")
    static <T> T callStatic(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        return (T) invoke(type, null, name, types, args);
    }

    private static Object invoke(Class<?> type, Object target, String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method m = type.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
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

    static Class<?>[] types(Class<?>... types) {
        return types;
    }

    /**
     * Number of file descriptors this JVM has open, or {@code -1} where the platform does not
     * report it (e.g. Windows); looked up reflectively so that no {@code com.sun.management}
     * class is needed at compile time.
     */
    static long openFileDescriptorCount() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        try {
            Method m = Class.forName("com.sun.management.UnixOperatingSystemMXBean")
                    .getMethod("getOpenFileDescriptorCount");
            if (!m.getDeclaringClass().isInstance(os)) {
                return -1;
            }
            return (Long) m.invoke(os);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
