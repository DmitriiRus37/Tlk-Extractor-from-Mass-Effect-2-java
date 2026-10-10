# Working context

What the agents learned while modernizing this project. Updated after every step.
Branch: `modernization`.

## Pipeline

| # | Step | Status |
|---|------|--------|
| 1 | Update dependencies + Java to 25 | done |
| 2 | Javadoc for all classes/methods | done |
| 3 | Tests (~80% coverage) + corner cases | done |
| 4 | Make it build/run on Java 1.8 – 25 | pending |
| 5 | Find bugs / refactor | pending |
| 6 | Implement 2 more `FileFormat` values | pending |

## Environment

- JDKs (`/usr/libexec/java_home -V`):
  - 25: `~/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home` (also 25, 25.0.1)
  - 11: `~/Library/Java/JavaVirtualMachines/ms-11.0.31/Contents/Home`
  - 8: `~/Library/Java/JavaVirtualMachines/corretto-1.8.0_504/Contents/Home` (Corretto 8 ships **no JavaFX**)
- Maven 3.10.0 (Homebrew) runs on JDK 27 by default, so always `export JAVA_HOME=...` before running `mvn`.
- Maven Central reachable.
- `.idea/misc.xml` gets an `ExternalStorageConfigurationManager` line written by IntelliJ. Don't commit it.

## Project facts

- Port of the C# tool https://github.com/jgoclawski/me2-tlk-tool. Package `ru`.
  - `TlkFile`: TLK → XML/TXT.
  - `HuffmanCompression`: XML → TLK.
  - Helpers: `BitArray` (copied from OpenJDK, GPLv2+CPE; the repo has no LICENSE file), `BitConverter` (.NET-style), `PositionInputStream`.
  - Enums: `Endian`, `FileFormat` (`TXT`, `CSV`, `XML`).
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

## Known bugs (to fix in step 5)

Re-enable the matching `@Disabled` test when fixing each one.

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
