#!/bin/sh
# Starts the GUI from the built jar with plain "java", without Maven's own startup time.
#
# - Builds the jar (tests skipped) and the runtime class path with Maven only when they are
#   missing or src/ or pom.xml changed since the last build (files or directories, so that
#   deleted and renamed files count).
# - Needs Java 25+ and runs the JDK in JAVA_HOME, as Maven does, or else the one "java" on the
#   PATH runs; puts the OpenJFX jars on the module path, as "mvn javafx:run" does.
# - On JDK 25+ uses an AOT cache (JEP 483/514/515): the first start after a build first runs
#   ru.AotTraining, which builds the window without showing it and converts the bundled
#   files/BIOGame_RUS.tlk to every format and back, and records which classes and methods that
#   uses in target/tlk-extractor.aot (a few seconds, once); every start then loads and links those
#   classes from the cache and JIT-compiles sooner.
#   The cache is recorded again whenever the JDK, the jar or the class path changes (an AOT
#   cache only checks the jar's size, so a stale one could run old code). Set TLK_NO_AOT=1 to
#   start without the cache.
# - On JDK 21 to 24, which have no AOT cache, the same training run records a dynamic CDS archive
#   (target/tlk-extractor.jsa, -XX:+AutoCreateSharedArchive), which the JVM itself records again
#   when the JDK or the jar changes. Measured on JDK 25 as a stand-in: JavaFX startup ~525 ->
#   ~370 ms; conversions do not get faster. TLK_NO_AOT=1 disables it too.
set -e
cd "$(dirname "$0")"

# The JDK and its version, read from its "release" file rather than by starting a JVM (~50 ms)
if [ -n "$JAVA_HOME" ]; then
    JDK=$JAVA_HOME
else
    JAVA_ON_PATH=$(command -v java) || { echo "TLK Extractor needs Java 25 or newer; no \"java\" found." >&2; exit 1; }
    JDK=$(dirname "$(dirname "$(readlink -f "$JAVA_ON_PATH" 2>/dev/null || echo "$JAVA_ON_PATH")")")
    if [ ! -f "$JDK/release" ] && [ -x /usr/libexec/java_home ]; then
        # macOS: /usr/bin/java is a launcher that runs the JDK java_home picks
        JDK=$(/usr/libexec/java_home 2>/dev/null || true)
    fi
fi
JAVA="$JDK/bin/java"
JAVA_VERSION=$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$JDK/release" 2>/dev/null || true)
if [ ! -x "$JAVA" ] || [ -z "$JAVA_VERSION" ]; then
    # an unusual layout: ask the java on the PATH
    JAVA=java
    JAVA_VERSION=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
fi
# "21", "25", ... ("1.8" on Java 8)
JAVA_MAJOR=${JAVA_VERSION%%.*}
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ]; then
    echo "TLK Extractor needs Java 25 or newer; $JAVA is version ${JAVA_VERSION:-unknown}." >&2
    exit 1
fi

JAR=target/tlk-extractor-1.0-SNAPSHOT.jar
CLASSPATH_FILE=target/classpath.txt
STAMP=target/run.stamp
AOT_CACHE=target/tlk-extractor.aot
AOT_KEY=$AOT_CACHE.key
CDS_ARCHIVE=target/tlk-extractor.jsa

if [ ! -f "$JAR" ] || [ ! -f "$CLASSPATH_FILE" ] || [ ! -f "$STAMP" ] \
        || [ -n "$(find src pom.xml -newer "$STAMP" | head -n 1)" ]; then
    mvn -q -DskipTests package dependency:build-classpath \
        -Dmdep.outputFile="$CLASSPATH_FILE" -DincludeScope=runtime
    touch "$STAMP"
    rm -f "$AOT_CACHE" "$CDS_ARCHIVE"
fi

DEPENDENCIES=$(cat "$CLASSPATH_FILE")
FX_PATH=$(echo "$DEPENDENCIES" | tr ':' '\n' | grep '/org/openjfx/' | paste -sd: - || true)
CLASS_PATH=$(echo "$DEPENDENCIES" | tr ':' '\n' | grep -v '/org/openjfx/' | paste -sd: - || true)

set -- --module-path "$FX_PATH" --add-modules javafx.controls,javafx.fxml \
    --enable-native-access=javafx.graphics -cp "$JAR${CLASS_PATH:+:$CLASS_PATH}"
if [ "$JAVA_MAJOR" -ge 25 ] && [ -z "$TLK_NO_AOT" ]; then
    # what the cache was recorded with: the exact JDK build, the jar and the class path
    KEY=$(cat "$JDK/release" "$JAR" "$CLASSPATH_FILE" 2>/dev/null | cksum)
    if [ "$(cat "$AOT_KEY" 2>/dev/null)" != "$KEY" ]; then
        rm -f "$AOT_CACHE"
    fi
    if [ ! -f "$AOT_CACHE" ]; then
        echo "Preparing the startup cache (once per build, a few seconds)..."
        # the recording run warns about every class it cannot cache (proxies, JFR events)
        if "$JAVA" -XX:AOTCacheOutput="$AOT_CACHE" -Xlog:aot=error "$@" ru.AotTraining files/BIOGame_RUS.tlk \
                >/dev/null 2>&1; then
            echo "$KEY" > "$AOT_KEY"
        else
            echo "Could not prepare the startup cache; starting without it." >&2
            rm -f "$AOT_CACHE"
        fi
    fi
    if [ -f "$AOT_CACHE" ]; then
        set -- -XX:AOTCache="$AOT_CACHE" "$@"
    fi
elif [ -z "$TLK_NO_AOT" ]; then
    # the JVM logs as errors that the JDK's own archive cannot use its module optimizations with
    # an extra module path; harmless, the archive is used
    set -- -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile="$CDS_ARCHIVE" -Xlog:cds=off "$@"
    if [ ! -f "$CDS_ARCHIVE" ]; then
        echo "Preparing the startup cache (once per build, a few seconds)..."
        "$JAVA" "$@" ru.AotTraining files/BIOGame_RUS.tlk >/dev/null 2>&1 \
            || echo "Could not prepare the startup cache; starting without it." >&2
    fi
fi

exec "$JAVA" "$@" ru.App
