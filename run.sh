#!/bin/sh
# Starts the GUI from the built jar with plain "java", without Maven's own startup time.
#
# - Builds the jar (tests skipped) and the runtime class path with Maven only when they are
#   missing or src/ or pom.xml changed since the last build.
# - Needs Java 21+; puts the OpenJFX jars on the module path, as "mvn javafx:run" does.
# - On JDK 25+ uses an AOT cache (JEP 483/514/515): the first start after a build records which
#   classes and methods the app uses and writes target/tlk-extractor.aot when the window is
#   closed; later starts load and link those classes from the cache and JIT-compile sooner.
#   On BIOGame_RUS.tlk this cut JavaFX startup from ~370 to ~205 ms and a cold
#   load/export/import round of all formats from ~1000 to ~780 ms.
#   Set TLK_NO_AOT=1 to start without the cache.
set -e
cd "$(dirname "$0")"

# "21", "25", ... ("1.8" on Java 8)
JAVA_VERSION=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
JAVA_MAJOR=${JAVA_VERSION%%.*}
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "TLK Extractor needs Java 21 or newer; \"java\" is version ${JAVA_VERSION:-unknown}." >&2
    exit 1
fi

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

DEPENDENCIES=$(cat "$CLASSPATH_FILE")
FX_PATH=$(echo "$DEPENDENCIES" | tr ':' '\n' | grep '/org/openjfx/' | paste -sd: - || true)
CLASS_PATH=$(echo "$DEPENDENCIES" | tr ':' '\n' | grep -v '/org/openjfx/' | paste -sd: - || true)

set -- --module-path "$FX_PATH" --add-modules javafx.controls,javafx.fxml \
    --enable-native-access=javafx.graphics -cp "$JAR${CLASS_PATH:+:$CLASS_PATH}"
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
