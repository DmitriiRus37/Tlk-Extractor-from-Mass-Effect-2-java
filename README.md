# Tlk-Extractor-from-Mass-Effect-2

## Overview

Project was rewritten from https://github.com/jgoclawski/me2-tlk-tool.

Tlk-Extractor is a program to modify TLK files of Mass Effect 2 (All text lines including Dialogs)

You can load your TLK-file to get output XML-file.
What is the reason of transforming TLK to XML? Because TLK-file is a non-editable binary file but XML is easy to edit the way you want.

## Requirements

- JDK 25 or newer (the build targets `maven.compiler.release` 25)
- Maven 3.6.3+

JavaFX (27) is pulled from Maven Central as regular dependencies, so no separate JavaFX SDK install is needed.

## Build and run

```bash
mvn clean package   # compile and build target/tlk-extractor-1.0-SNAPSHOT.jar
mvn javafx:run      # launch the GUI
```

If `mvn -v` reports a JDK older than 25, point `JAVA_HOME` at a JDK 25+ installation first.
