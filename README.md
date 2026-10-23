# Tlk-Extractor-from-Mass-Effect-2

## Overview

Project was rewritten from https://github.com/jgoclawski/me2-tlk-tool.

Tlk-Extractor is a program to modify TLK files of Mass Effect 2 (All text lines including Dialogs)

You can load your TLK-file to get an XML, TXT or CSV file, edit it and convert it back to TLK.
What is the reason of transforming TLK to text? Because TLK-file is a non-editable binary file but XML, TXT and CSV are easy to edit the way you want.

## Text formats

All three formats can be converted in both directions ("Create XML/TXT/CSV" tab: TLK to text,
"Create Tlk" tab: text to TLK; pick the format in the tab, or just use a file name ending in
`.xml`, `.txt` or `.csv`). Each one is UTF-8 and holds the same information: the tool version and,
for every string ref of the TLK file in file order, its id, its position, whether it has text and
the text. Converting a TLK file to any of them and back gives the same strings again (for the
bundled sample, exporting the re-encoded file to XML gives a byte-identical XML).

The same rules apply on import whatever the format: files written by tool versions before 1.0.3 are
rejected, entries are sorted by position, the first entry of an id goes to the male section and a
second one to the female section (a third one is an error), CRLF line breaks in texts become LF (as
in the C# original), and the "Test version" box prefixes every localizable string with its id.

### XML

The format of the original C# tool.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tlkFile TLKToolVersion="1.0.4">
    <!--Male entries section begin (ends at position 103969)-->
    <string>
        <id>34931</id>
        <position>0</position>
        <data>Text of the string</data>
    </string>
    <string>
        <id>-2147319074</id>
        <position>1</position>
        <data>-1</data>
    </string>
    ...
</tlkFile>
```

- `<data>-1</data>` marks an entry without text. An entry is taken to have text exactly if its id is
  not negative; where that is not the case, `<data>` gets `hasText="true"` or `hasText="false"`.
- A carriage return is written as `&#13;`. Characters XML 1.0 does not allow (control characters
  such as U+0001, unpaired surrogates) cannot be exported to XML; use TXT for such files.

### TXT

Plain text with one line per entry, for any text editor.

```text
# TLKToolVersion=1.0.4
# Male entries section begin (ends at position 103969)
34931: Text of the string
-2147319074: \N
218733: First line\nsecond line with a \\ backslash
```

- The first line must be the header `# TLKToolVersion=<version>`.
- Every entry is `<id>: <text>`. On import, one space after the colon is optional and any further
  spaces belong to the text (so keep your editor from trimming trailing spaces in texts that end
  with one).
- `\N` as the whole text means "no text"; an empty text is just `<id>: `.
- Escapes: `\\` backslash, `\n` line feed, `\r` carriage return, `\t` tab, `\uXXXX` any UTF-16
  unit (written for other control characters, U+2028/U+2029 and unpaired surrogates). Any other
  backslash sequence is an error.
- The order of the lines is the position order: there are no explicit positions, so to move a
  string, move its line.
- Lines starting with `#` (such as the section markers) and blank lines are ignored. The file is
  written with CRLF line ends; CRLF, LF and CR are all accepted, as is a UTF-8 byte order mark.
- The old plain-text export (`<id>: <text>` lines without header, `null` for no text, raw line
  breaks) could not be read back and is rejected.

### CSV

Comma-separated values as in [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180), for Excel or
LibreOffice Calc.

```text
TLKToolVersion,1.0.4
id,position,hasText,text
34931,0,true,Text of the string
-2147319074,1,false,
218733,2,true,"First line
second line, with ""quotes"""
```

- The first record holds the tool version, the second is the header row; then one record per entry.
- `hasText` is `true` or `false` (any case, so Excel's `TRUE`/`FALSE` work); an entry with
  `false` must have an empty `text`. This tells an empty text from no text.
- Fields with a comma, a double quote or a line break are enclosed in double quotes, with double
  quotes doubled; line breaks inside texts stay as they are.
- The file is written with a UTF-8 byte order mark (so that Excel shows Cyrillic correctly) and
  CRLF record ends; on import the byte order mark is optional and CRLF, LF and CR are accepted.
- On import, columns are found by their header names (in any order, any case) and other columns are
  ignored; rows may be reordered (they are sorted by `position`); empty rows are skipped.
- Excel tip: Excel splits CSV files at the list separator of your locale, which is `;` in many
  European locales (Russian included). There, open the file with Data > From Text/CSV and choose
  "Comma" as delimiter and UTF-8 as encoding, and keep the `text` column as text so that Excel does
  not turn strings into numbers, dates or formulas.

Errors in a TXT or CSV file are reported with the line number, e.g.
`Invalid escape sequence "\x" at column 5 (valid: \\ \n \r \t \uXXXX) (line 2 of strings.txt)`.

## Requirements

- JDK 25 or newer. The code is compiled with `--release 25`, so the jar needs a Java 25+
  runtime.
- Maven 3.6.3+.

### JavaFX

The GUI needs JavaFX: Maven takes OpenJFX 27.0.1 from Maven Central (it needs JDK 25 or newer).
No separate JavaFX SDK install is needed.

## Build and run

```bash
mvn clean verify          # compile, run the tests, check coverage; builds target/tlk-extractor-1.0-SNAPSHOT.jar
mvn javadoc:javadoc       # API documentation in target/reports/apidocs
```

Run the GUI:

- `mvn javafx:run` (puts JavaFX on the module path).
- Faster on macOS/Linux: `./run.sh` starts the GUI from the jar with plain `java` (Maven runs
  only when the jar is missing or `src/` or `pom.xml` changed). On JDK 25+ it also uses an
  [AOT cache](https://openjdk.org/jeps/514): after each build it first spends a few seconds on a
  hidden training run (`ru.AotTraining`: builds the window without showing it and converts the
  bundled sample to every format and back) and records `target/tlk-extractor.aot`; every start
  then uses it. On the bundled sample JavaFX startup drops from ~580 to ~300 ms and a cold
  load/export/import round of all formats from ~835 to ~610 ms. The cache is recorded again when
  the JDK, the jar or the class path changes; `TLK_NO_AOT=1 ./run.sh` starts without it. On
  JDK 21-24, which have no AOT cache, the training run records a dynamic CDS archive
  (`target/tlk-extractor.jsa`) instead, which speeds up JavaFX startup (~525 → ~370 ms, measured
  on JDK 25) but not the conversions.

However the GUI is started, once its window is open it converts a small generated file through
every format in the background (`ru.WarmUp`), so that the JIT has compiled the conversion code by
the time you have picked a file: the first conversion of the bundled sample gets ~150 ms faster.

Maven uses the JDK in `JAVA_HOME` (check with `mvn -v`); point it at another JDK to switch, e.g.
`JAVA_HOME=/path/to/jdk-25 mvn javafx:run`.

The jar is not a self-contained runnable jar: it has no `Main-Class` entry and does not bundle
JavaFX, so start the GUI through Maven or `run.sh` as above rather than with `java -jar`.

GitHub Actions (`.github/workflows/build.yml`) builds and tests the project on JDK 25.
