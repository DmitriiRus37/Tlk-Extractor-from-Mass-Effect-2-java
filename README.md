# Tlk-Extractor-from-Mass-Effect-2

## Overview

Project was rewritten from https://github.com/jgoclawski/me2-tlk-tool.

Tlk-Extractor is a program to modify TLK files of Mass Effect 2 (All text lines including Dialogs)

You can load your TLK-file to get output XML-file.
What is the reason of transforming TLK to XML? Because TLK-file is a non-editable binary file but XML is easy to edit the way you want.

## Requirements

- JDK 8 to 25 (newer JDKs should work as long as they can still compile for Java 8). The code
  is compiled for Java 8 on every JDK (`--release 8` on JDK 9+, `-source/-target 1.8` on JDK 8),
  so the jar runs on any Java 8+ runtime.
- Maven 3.6.3+ (Maven 3.x runs on JDK 8 too).

### JavaFX

The GUI needs JavaFX. Maven picks it according to the JDK that runs the build (JDK-activated
profiles in `pom.xml`):

| JDK running Maven | JavaFX used                                                   |
|-------------------|---------------------------------------------------------------|
| 8                 | the one bundled with the JDK, if any (see below)              |
| 11 – 16           | OpenJFX 17.0.20 from Maven Central                            |
| 17 – 22           | OpenJFX 21.0.12                                               |
| 23 – 24           | OpenJFX 25.0.4                                                |
| 25 and newer      | OpenJFX 27                                                    |

OpenJFX is only published to Maven Central for JDK 11+, and each release line needs a minimum JDK,
so every range gets the newest release it can run. No separate JavaFX SDK install is needed there.

On JDK 8, JavaFX is part of the JDK only for some vendors: Azul Zulu "JDK FX", BellSoft Liberica
"Full JDK" and Oracle JDK 8 ship `jre/lib/ext/jfxrt.jar`, which the build then uses automatically.
Other JDK 8 builds (Amazon Corretto 8, Eclipse Temurin 8, ...) have no JavaFX: there the build
leaves out the GUI classes (`App`, `FxmlController`) and their tests and builds and tests only the
TLK conversion classes (`TlkFile`, `HuffmanCompression`, ...), so the jar has no GUI. JDK 9 and
10 (long out of support) are treated the same way.

## Build and run

```bash
mvn clean verify          # compile, run the tests, check coverage; builds target/tlk-extractor-1.0-SNAPSHOT.jar
mvn javadoc:javadoc       # API documentation in target/reports/apidocs
```

Run the GUI:

- JDK 11 or newer: `mvn javafx:run` (puts JavaFX on the module path).
- JDK 8 with a bundled JavaFX (see above): `mvn compile exec:java`.
- JDK 8 without JavaFX: not possible, use one of the JDKs above.

Maven uses the JDK in `JAVA_HOME` (check with `mvn -v`); point it at another JDK to switch, e.g.
`JAVA_HOME=/path/to/jdk-11 mvn javafx:run`.

The jar is not a self-contained runnable jar: it has no `Main-Class` entry and does not bundle
commons-io or JavaFX, so start the GUI through Maven as above rather than with `java -jar`.

GitHub Actions (`.github/workflows/build.yml`) builds and tests the project on JDK 8 (with and
without JavaFX), 11, 17, 21 and 25.
