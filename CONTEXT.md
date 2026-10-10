# Working context

What the agents learned while modernizing this project. Updated after every step.
Branch: `modernization`.

## Pipeline

| # | Step | Status |
|---|------|--------|
| 1 | Update dependencies + Java to 25 | done |
| 2 | Javadoc for all classes/methods | done |
| 3 | Tests (~80% coverage) + corner cases | done |
| 4 | Make it build/run on Java 1.8 – 25 | done |
| 5 | Find bugs / refactor | done |
| 6 | Implement 2 more `FileFormat` values (TXT, CSV in both directions) | done |

## Environment

- JDKs (`/usr/libexec/java_home -V`):
  - 25: `~/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home` (also 25, 25.0.1)
  - 11: `~/Library/Java/JavaVirtualMachines/ms-11.0.31/Contents/Home`
  - 8: `~/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home` (Corretto 8 ships **no JavaFX**)
- Maven 3.10.0 (Homebrew) runs on JDK 27 by default, so always `export JAVA_HOME=...` before running `mvn`.
- Maven Central reachable.
- **JDK 27 (Homebrew default) hangs** in `TlkFileSampleTest` for over 10 minutes: the quadratic per-bit `LinkedList.get` decoding (bug #10) runs pathologically slowly there. JDK 25 runs the whole suite in about 15 s. Fixed in step 5 (#10).
- `.idea/misc.xml` gets an `ExternalStorageConfigurationManager` line written by IntelliJ. Don't commit it.

## Project facts

- Port of the C# tool https://github.com/jgoclawski/me2-tlk-tool. Package `ru`.
  - `TlkFile`: TLK → XML/TXT.
  - `HuffmanCompression`: XML → TLK.
  - Helpers: `BitArray` (copied from OpenJDK, GPLv2+CPE; the repo has no LICENSE file), `BitConverter` (.NET-style), `PositionInputStream`.
  - `FileFormat` (`TXT`, `CSV`, `XML`); `Endian` was removed in step 5.
  - Format I/O (since step 6): `XmlFormat`, `TxtFormat`, `CsvFormat`, sharing `TlkEntry`, `InputFile` and `FormatWriter`.
  - GUI: `App` and `FxmlController`, with `app.fxml`.
- TLK layout:
  - Little-endian header: magic `7040084` (`"Tlk\0"`), version, min version, male entries, female entries, tree nodes, data length.
  - String-ref entries: (id, bit offset). The offset is `-1` for entries with no text, and negative ids mark those entries.
  - Huffman tree nodes: pairs of int32 (`>= 0` points to a child node, a negative value encodes a leaf char as `0xffff - v`).
  - Bit-packed data.
- Sample `files/BIOGame_RUS.tlk` (2.8 MB, Russian):
  - Header: magic 7040084, version 3, 103,970 male + 1,154 female entries = 105,124 refs, 189 tree nodes.
  - Strings: 57,377 decoded; 47,747 entries have id < 0 and offset −1.
  - Shared data: 4,084 shared offsets and 3,524 mid-string (suffix-shared) offsets.
  - Only ids 0x8000001–0x8000003 carry the `0x8000000` "localizable" bit.
- XML export of the sample is ~17.8 MB with 105,124 `<string>` elements.
- The XML carries a `TLKToolVersion` attribute; `HuffmanCompression` requires it to be ≥ 1.0.3.

## Step 1: dependencies and Java 25

- Baseline did not compile on JDK 25:
  - `javafx-fxml` wasn't declared. It only worked through the IDE's Corretto 8 + bundled JavaFX.
  - Lombok wasn't run: JDK 23+ needs `annotationProcessorPaths`.
- Versions:
  - lombok 1.18.38 → 1.18.48, now `provided` scope and set as the annotation processor.
  - JavaFX controls 24.0.1 → 27, plus graphics and fxml declared. JavaFX 27 is bytecode 69, so it needs JDK 25 or newer.
  - commons-io 2.19.0 → 2.22.0.
  - `io.sarl.javafx` 0.9.0 removed: it was unused and didn't even provide fxml.
- Plugins, all pinned:
  - compiler 3.16.0 (`release` 25), resources 3.5.0, surefire 3.6.0, jar 3.5.1
  - clean 3.5.0, install 3.2.0, deploy 3.2.0, site 3.22.0
  - versions 2.22.0, with pre-releases ignored
  - javafx-maven-plugin 0.0.8, `mvn javafx:run` with `--enable-native-access=javafx.graphics`
- Lombok 1.18.48 prints a `sun.misc.Unsafe` warning on JDK 25. It is harmless. Silencing it via `.mvn/jvm.config` would break Maven on JDKs older than 23.

## Step 2: Javadoc

- Every class, method and field is documented, plus `package-info.java`; the TLK layout is described in the `TlkFile` class doc.
- maven-javadoc-plugin 3.12.0 with `<show>private</show>`. Doclint went from 54 errors + 181 warnings to 0/0.
- To avoid "default constructor" warnings, Lombok `@NoArgsConstructor`/`@AllArgsConstructor` were replaced with hand-written constructors (`FxmlController`, `TlkFile.Wrap`) and explicit empty constructors were added. Bytecode is identical apart from member order.

## Step 3: tests

- JUnit Jupiter 5.14.4 (JUnit 6 needs Java 17). No Mockito or AssertJ. JaCoCo 0.8.15.
  - `mvn verify` enforces line ≥ 0.90 and branch ≥ 0.85, excluding `App`/`FxmlController`.
  - Surefire passes `-Dfile.encoding=UTF-8`.
- 238 test methods (213 enabled, 25 `@Disabled("Known bug: …")`); `mvn clean verify` takes about 20 s.
- Coverage: overall 94.0% line / 95.8% branch, core 98.9% / 99.1%, GUI 49%.
- Helpers in `src/test/java/ru`: `TestSupport`, `TlkTestFile` (synthetic TLK builder), `ExportedXml`, `SampleFixture`, `HuffmanAccess` (contains a correct TLK writer), `JavaFxToolkit`, `ExportMain`. Test XML lives in `src/test/resources/xml/`.
- The tests use **reflection on private members** (`TestSupport`, `HuffmanAccess`). Renaming those members means updating the helpers too.
- GUI tests use JavaFX's headless platform (`glass.platform=Headless`, available only in JavaFX 25+) and are skipped when no toolkit starts. The file-chooser handlers and `App.main` are not covered.
- Already Java 8-safe: test sources compile with `--release 8`, and JUnit 5.14 / JaCoCo 0.8.15 / Surefire 3.6 run on Java 8.

## Step 4: Java 1.8 – 25

- Single module with JDK-activated profiles; all main, test and jar classes are major version **52**.
  - Base build: `source/target 1.8`, `-Xlint:-options`.
  - `release-8` profile (JDK 9+): `maven.compiler.release=8`, also used for javadoc.
  - `no-javafx` profile: JDK 8–10 when `${java.home}/lib/ext/jfxrt.jar` is missing.
    - Excludes `App`/`FxmlController` and `AppTest`/`FxmlControllerTest`/`JavaFxToolkit`.
    - JDK 8 javadoc parses every file of a package on its source path, so the profile copies the non-GUI sources to `target/javadoc-sources` for javadoc.
  - JDK 8 with a bundled `jfxrt.jar` (Zulu FX, Liberica Full, Oracle) needs nothing special.
  - `openjfx` profile (JDK 11+) adds the JavaFX deps + javafx-maven-plugin. `javafx-native-access` (JDK 24+) adds `--enable-native-access` (JDK 11–16 reject the flag).
  - `exec-maven-plugin` 3.6.4 runs the GUI on a JDK 8 with bundled FX (`mvn compile exec:java`).
- OpenJFX version per JDK (minimum JDK checked from the class versions in the jars):

  | JDK running Maven | JavaFX |
  |---|---|
  | [11,17) | 17.0.20 (class 55) |
  | [17,23) | 21.0.12 (class 61) |
  | [23,25) | 25.0.4 (class 67) |
  | [25,) | 27 (class 69) |

- All plugins already run on Java 8 (each declares `requiredJavaVersion` 1.8); no overrides were needed.
- Core decoupled from the GUI: `TlkFile.storeToFile(String, FileFormat, DoubleConsumer)` replaces the unused `FxmlController` parameter. It is still never called.
- Results:
  - Corretto 8: 278 tests run / 24 skipped.
  - JDK 11: 289 / 26; the JavaFX 17 toolkit starts on the macOS display.
  - JDK 25: 289 / 25, headless.
  - Coverage check passes everywhere.
- CI: `.github/workflows/build.yml` runs a matrix of 8 Zulu `jdk+fx`, 8 Temurin (the no-FX path) and 11/17/21/25 Temurin. Not yet run.
- Limits:
  - No GUI on JDK 8 without FX or on JDK 9/10.
  - The JDK 8 + FX path is untested locally.
  - GUI tests skip on headless Linux CI except on JDK 25 (`xvfb-run` would fix that).
  - Bug #11 (charset) is masked in tests by surefire's `-Dfile.encoding=UTF-8` but will hit real JDK 8 users.

## Step 5: bug fixes and refactoring

- Results:
  - All 20 known bugs fixed, plus about 12 new ones; 0 `@Disabled` tests remain.
  - Tests: JDK 25 324/0 skipped, JDK 11 324/1 skipped (`AppTest` real window), Corretto 8 312/0 (GUI excluded).
  - Core coverage 99.2% line / 97.3% branch; suite takes about 9 s on JDK 25.
- Performance on the sample: XML export went from 17.7 s to about 0.1 s. The full TLK→XML→TLK→XML round trip takes about 0.8 s, and the second XML is byte-identical to the first.
- Re-encoded TLK size: 2,833,282 bytes against 2,823,069 for the original (+0.36%), because the writer doesn't share string tails.
- New bugs fixed:
  - **XXE:** DOM parsing allowed DTD and external entities; reading is now StAX with both off.
  - Node index out of range, string without terminator, and negative header counts now give clear errors.
  - Lone CR is kept as `&#13;`; text XML can't represent (lone surrogates, U+FFFE/U+FFFF) is rejected.
  - The Huffman tree is deterministic (PriorityQueue with first-occurrence tie-break).
  - A failed load keeps the previous content.
  - Whitespace around `<id>`/`<position>` is accepted; a wrong root element is rejected.
  - Timings are no longer printed to stdout.
- XML format changes:
  - It now starts with `<?xml version="1.0" encoding="UTF-8"?>`.
  - Optional `hasText="true|false"` on `<data>`, written only when the id's sign is misleading. It fixes the `-1` ambiguity (#8).
  - Otherwise the sample's XML is byte-identical to the old tool's output.
- GUI:
  - Conversions run in a `javafx.concurrent.Task` with real progress through `ProgressReporter` (at most one update per percent).
  - Save dialogs for outputs; cancel is null-safe; the tab is locked while running; status shows `DONE` / `FAILED: <reason>`.
  - The debug `(#id)` prefix is behind a new "Test version" checkbox, off by default.
  - FXML ids `textFieldIOutputPath*` renamed to `textFieldOutputPath*`; new `checkBoxDebugXmlToTlk`.
- API changes:
  - `TlkFile`:
    - `loadTlkData(String)` (no `isPC`).
    - `storeToFile(String, FileFormat, DoubleConsumer)` now throws only `IOException`, and the progress callback is actually used.
  - `HuffmanCompression`:
    - `loadInputData(String, FileFormat, boolean)` throws `IOException`, or `IllegalArgumentException` for non-XML input.
    - `saveToTlkFile(String, DoubleConsumer)`.
    - Internals renamed to camelCase: `readXml`, `prepareHuffmanCoding`, `buildCodes`, `treeToBuffer()` returning `int[]`, `BitWriter`, `compareVersions`.
    - `TlkEntry.stringId`; constants `MIN_TOOL_VERSION`, `DEFAULT_TOOL_VERSION`, `NON_LOCALIZABLE_FLAG`.
  - `BitConverter`:
    - `getBytesLittleEndian(int)` and `toCharLittleEndian(byte[], byteIndex)`.
    - Unchecked exceptions instead of `throws Exception`.
  - Removed: `Endian`, `TlkFile.Wrap`/`GetString`/`baseStreamSeek`/`prettyXmlFile`, `BitArray.getBit`, `App.getPrimaryStage`, the controller's dead fields.
  - New `TlkHeader` constants (`SIZE`, `MAGIC`, `XBOX_MAGIC`, …) and `write(OutputStream)` on header, string-ref and node.
- **Format dispatch:** `storeToFile` and `loadInputData` both `switch` on `FileFormat`; a new format just adds a `case`. Superseded in step 6: the XML reader/writer and the `XML_*` constants moved to `XmlFormat`, and `loadInputData` accepts all three formats.
- Facts from the C# original (jgoclawski/me2-tlk-tool):
  - The localizable mask really is `0x8000000` (bit 27).
  - The debug prefix is applied only to ids ≥ 0 that lack that bit.
  - Xbox files are big-endian, magic 1416391424.
  - Entries use a `Dictionary`, which keeps insertion order and throws on a third occurrence of an id.
  - The tree is written breadth-first, right child first.
  - No string deduplication; no byte-reproducible output (unstable sort).
  - Pre-1.0.3 XML only triggers a warning in C#; Java rejects it.
  - `\r\n` is replaced by `\n` on import.
  - The GUI used a BackgroundWorker and a "Test version" checkbox, off by default; `TLKToolVersion` is 1.0.4.
- Deliberately not done:
  - `BitConverter`/`PositionInputStream` kept although the conversion code no longer uses them; they could be deleted.
  - No tail sharing in the writer.
  - No Xbox (big-endian) support.
  - Female-only ids aren't recoverable because the XML has no section marker.

## Step 6: TXT and CSV formats

- `TXT` and `CSV` now work in both directions, like XML. On the sample, TLK→fmt→TLK→XML is byte-identical to the direct XML export for both formats.
- **TXT** (`TxtFormat`):
  - Header line `# TLKToolVersion=1.0.4`, then one `<id>: <text>` line per entry; line order is position order.
  - `\N` means no text. Escapes: `\\`, `\n`, `\r`, `\t`, `\uXXXX`.
  - `#` comments and blank lines are ignored.
  - Written with CRLF; any line ending is read; an optional BOM is accepted.
  - The old headerless listing is rejected.
- **CSV** (`CsvFormat`, RFC 4180):
  - BOM + CRLF, a `TLKToolVersion,1.0.4` record, then the header `id,position,hasText,text`.
  - Columns are found by name in any order and case; `hasText` accepts `true`/`false` in any case.
  - Strict quoting with line-numbered errors.
  - Can't hold unpaired surrogates (use TXT).
- Shared model:
  - Entry classes: `TlkEntry`, `InputFile` (version + entries), `FormatWriter` (`begin`/`comment`/`entry`/`end`).
  - Every format goes through the same version check, CRLF→LF, debug prefix, position sort, third-id rejection and Huffman build.
  - `FileFormat.getExtension()` and `fromFileName(String)`.
- GUI:
  - `ChoiceBox<FileFormat>` per tab (`choiceBoxFormatTlkToXml`, `choiceBoxFormatXmlToTlk`), defaulting to XML. The format follows the file extension, and the output extension follows the format.
  - Per-format file filters. The first tab is renamed "Create XML/TXT/CSV"; the window is 40 px taller.
- Sample round trips (warm JVM, JDK 25):

  | Format | File size | Full chain |
  |---|---|---|
  | TXT | 7.54 MB | ~0.52 s |
  | CSV | 8.58 MB | ~0.52 s |
  | XML | 17.8 MB | ~0.58 s |

- Tests: JDK 25 417/0 skipped, JDK 11 417/1 skipped, Corretto 8 398/0. Core coverage 99.4% line / 97.5% branch.
- Limitations:
  - CRLF inside texts becomes LF in every format (as in the C# tool).
  - TXT trailing spaces can be lost to editors that trim them.
  - Excel in `;`-separator locales needs Data > From Text/CSV.
  - The save dialog doesn't append a missing extension.

## Known bugs found in steps 1–3

**All fixed in step 5.** Each one's `@Disabled` test is enabled again and passes. Kept for history.

### HuffmanCompression (XML → TLK)

1. `saveToTlkFile` writes ints with `OutputStream.write(int)`, i.e. only the low byte, so the generated TLK is corrupt.
   - Tests: `savedTlkLoadsBack`, `tlkToXmlToTlkRoundTrip`.
2. Entries are kept in a `HashMap`, so position order is lost (`savedTlkKeepsThePositionOrder`). A third entry with the same id silently overwrites the second (`thirdOccurrenceOfAnIdIsRejected`). Tree serialization depends on HashMap iteration order.
3. A missing `TLKToolVersion` throws an NPE (`missingToolVersionIsRejectedAsTooOld`), and the null check after it is dead code. Old versions throw a bare `RuntimeException()`.
4. Empty input (or entries with no text only) throws `IndexOutOfBoundsException`.
   - Tests: `emptyStringListCanBeLoaded`, `onlyEntriesWithoutTextCanBeLoaded`.
5. When every string is empty, the Huffman code has zero bits (`singleDistinctCharacterGetsANonEmptyCode`).
6. Reusing an instance mixes trees and codes, because `huffmanTree`/`huffmanCodes` are never cleared (`reusingAnInstanceDoesNotMixTrees`).
7. Smaller issues:
   - `ff` is ignored; `tlkFileElements` is unused.
   - The "localizable" mask `0x8000000` is probably meant to be `0x80000000`.
   - No string deduplication or suffix sharing, unlike the game's own files.
8. XML round trip: TlkFile writes `<data>-1</data>` when the offset is < 0, but HuffmanCompression treats data as an offset only when id < 0. An id ≥ 0 with offset −1 comes back as the literal text "-1".

### TlkFile (TLK → XML)

9. `loadTlkData` opens the file 3 times and leaks 2 handles (`loadingDoesNotLeakFileHandles`). It ignores short `read()` results and `skip()` return values (`fileTruncatedInDataBlockIsEof`). Bad magic throws a message-less `RuntimeException`, and `isPC` is unused.
10. **Quadratic export:** `get(i)` on a `LinkedList` (`xmlExportTimeGrowsLinearly`). XML export takes 14.3 s (0.8 s with an `ArrayList`) and TXT 21.8 s (2.9 s); decoding calls `characterTree.get()` once per bit.
11. Default charset: XML is written with the platform charset but declared UTF-8, which fails on JDK < 18 on Windows or with `LANG=C` (`exportIsUtf8WhateverTheDefaultCharset`). **This matters for Java 8.**
12. `storeToFile`/`saveToXmlFile` never close their `FileWriter`/`PrintWriter`, leaking 2 handles per export (`exportDoesNotLeakFileHandles`).
13. `prettyXmlFile`:
    - Loads the whole file into a String and a DOM.
    - Drops the XML declaration.
    - Its exception message contains the entire XML.
14. Missing male-end/female-begin comments when there are no female entries (`sectionCommentsAreBalancedWithoutFemaleEntries`).
15. TXT export with zero entries creates no file (`zeroEntryFileGivesEmptyTextFile`), swallows IOExceptions (`unwritableTargetFails`), and reopens the file for every line.

### Helpers

16. `BitConverter`:
    - `toInt16` checks for length 8 instead of 2 (`readsTwoByteArray`).
    - `GetBytes(int)` uses native byte order (`nativeOrderIntIsLittleEndianOnEveryPlatform`).
    - `int64BitsToDouble` just casts (`int64BitsToDoubleIsInverseOfDoubleToInt64Bits`).
    - `getBytes(char)` is little-endian while `toChar` is big-endian.
    - `getBytes(String)` uses the default charset.
    - `toCharRev` reverses the input in place (`doesNotModifyTheInputArray`) and misaligns odd lengths (`oddLengthArrayReadsTheFirstPair`).
    - The `to*` methods require exact lengths even though their messages say "at least".
17. `BitArray`:
    - `toString()` throws on zero length (`emptyArrayPrintsEmptyString`).
    - `truncate()` keeps whole bytes.
    - `getRev` allocates a `boolean[8]` per bit.
18. `PositionInputStream.skipNBytes(int)` is an empty overload of `skipNBytes(long)`, so int arguments silently do nothing (`skipNBytesSkipsExactlyAndAdvancesPosition`).

### GUI

19. `FxmlController`:
    - Cancelling the dialog in `selectInputXmlFile`/`selectOutputTlkFile` throws an NPE.
    - The output pickers use `showOpenDialog` instead of `showSaveDialog`.
    - Conversions run on the FX thread, so the UI freezes and the progress bars never move.
    - Failures leave the status at "in progress" (`failedConversionIsReportedInTheStatus`).
    - `debugVersion=true` is hard-coded, which prefixes `(#id) ` to every string.
    - There are dead fields.
20. `App.main` doesn't pass `args` on, and `TlkEntry` implements raw `Comparable`.
