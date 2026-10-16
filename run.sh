#!/bin/sh
# Starts the GUI from the built jar with plain "java", without Maven's own startup time.
#
# - Builds the jar (tests skipped) and the runtime class path with Maven only when they are
#   missing or src/ or pom.xml changed since the last build.
# - On JDK 11+ puts the OpenJFX jars on the module path, as "mvn javafx:run" does; on JDK 8 uses
#   the JDK's bundled JavaFX.
# - On JDK 25+ uses an AOT cache (JEP 483/514/515): the first start after a build records which
#   classes and methods the app uses and writes target/tlk-extractor.aot when the window is
#   closed; later starts load and link those classes from the cache and JIT-compile sooner.
#   On BIOGame_RUS.tlk this cut JavaFX startup from ~370 to ~205 ms and a cold
#   load/export/import round of all formats from ~1000 to ~780 ms.
#   Set TLK_NO_AOT=1 to start without the cache.
set -e
cd "$(dirname "$0")"

JAR=target/tlk-extractor-1.0-SNAPSHOT.jar
CLASSPATH_FILE=target/classpath.txt
STAMP=target/run.stamp
AOT_CACHE=target/tlk-extractor.aot

if [ ! -f "$JAR" ] || [ ! -f "$CLASSPATH_FILE" ] || [ ! -f "$STAMP" ] \
        || [ -n "$(find src pom.xml -type f -newer "$STAMP" | head -n 1)" ]; then
    mvn -q -DskipTests package dependency:build-classpath \
        -Dmdep.outputFile="$CLASSPATH_FILE" -DincludeScope=runtime
    touch "$STAMP"
    rm -f "$AOT_CACHE"
fi

# "1.8" on JDK 8, "11", "25", ... later
JAVA_VERSION=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
JAVA_MAJOR=${JAVA_VERSION#1.}
JAVA_MAJOR=${JAVA_MAJOR%%.*}

DEPENDENCIES=$(cat "$CLASSPATH_FILE")
FX_PATH=$(echo "$DEPENDENCIES" | tr ':' '\n' | grep '/org/openjfx/' | paste -sd: - || true)
CLASS_PATH=$(echo "$DEPENDENCIES" | tr ':' '\n' | grep -v '/org/openjfx/' | paste -sd: - || true)

set -- -cp "$JAR${CLASS_PATH:+:$CLASS_PATH}"
if [ -n "$FX_PATH" ]; then
    set -- --module-path "$FX_PATH" --add-modules javafx.controls,javafx.fxml "$@"
    if [ "$JAVA_MAJOR" -ge 22 ]; then
        set -- --enable-native-access=javafx.graphics "$@"
    fi
fi
if [ "$JAVA_MAJOR" -ge 25 ] && [ -z "$TLK_NO_AOT" ]; then
    if [ -f "$AOT_CACHE" ]; then
        set -- -XX:AOTCache="$AOT_CACHE" "$@"
    else
        echo "Recording the AOT cache: it is written when you close the window, and later starts are faster."
        # the recording run warns about every class it cannot cache (proxies, JFR events)
        set -- -XX:AOTCacheOutput="$AOT_CACHE" -Xlog:aot=error "$@"
    fi
fi

exec java "$@" ru.App
