package ru;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CsvFormat}: quoting, the writer and the RFC 4180 reader with its error messages. */
class CsvFormatTest {

    private static final String HEAD = "TLKToolVersion,1.0.4\r\nid,position,hasText,text\r\n";

    @TempDir
    Path dir;

    private int fileCounter;

    private Path file(String content) throws IOException {
        return TestSupport.writeUtf8(dir.resolve("in" + (fileCounter++) + ".csv"), content);
    }

    private InputFile read(String content) throws IOException {
        return CsvFormat.read(file(content));
    }

    /** Asserts that reading fails with a message containing {@code text} and naming the line. */
    private IOException assertReadFails(String content, String text, int line) throws IOException {
        Path f = file(content);
        IOException e = assertThrows(IOException.class, () -> CsvFormat.read(f));
        assertTrue(e.getMessage().contains(text), e.getMessage());
        assertTrue(e.getMessage().endsWith("(line " + line + " of " + f + ")"), e.getMessage());
        return e;
    }

    private static List<String> texts(InputFile in) {
        List<String> result = new ArrayList<>();
        for (TlkEntry e : in.entries) {
            result.add(e.data);
        }
        return result;
    }

    private static List<String> idsAndPositions(InputFile in) {
        List<String> result = new ArrayList<>();
        for (TlkEntry e : in.entries) {
            result.add(e.stringId + "@" + e.position);
        }
        return result;
    }

    private static TlkFile.TlkStringRef ref(int id, int position, String data) {
        TlkFile.TlkStringRef r = new TlkFile.TlkStringRef(id, data == null ? -1 : 0);
        r.position = position;
        r.data = data;
        return r;
    }

    @Nested
    class Quoting {

        @Test
        void plainFieldsAreNotQuoted() {
            for (String field : Arrays.asList("", "Hello", "Привет мир", " spaces ", "tab\there", "it's", "a;b")) {
                assertSame(field, CsvFormat.quoteIfNeeded(field));
            }
        }

        @Test
        void fieldsWithSeparatorQuoteOrLineBreakAreQuoted() {
            assertEquals("\"a,b\"", CsvFormat.quoteIfNeeded("a,b"));
            assertEquals("\"say \"\"hi\"\"\"", CsvFormat.quoteIfNeeded("say \"hi\""));
            assertEquals("\"\"\"\"", CsvFormat.quoteIfNeeded("\""));
            assertEquals("\"a\nb\"", CsvFormat.quoteIfNeeded("a\nb"));
            assertEquals("\"a\rb\"", CsvFormat.quoteIfNeeded("a\rb"));
            assertEquals("\"a\r\nb\"", CsvFormat.quoteIfNeeded("a\r\nb"));
        }
    }

    @Nested
    class Writing {

        @Test
        void writesBomVersionHeaderAndOneRecordPerEntry() throws IOException {
            StringWriter out = new StringWriter();
            FormatWriter writer = CsvFormat.writer(out);
            writer.begin("1.0.4");
            writer.comment("ignored");
            writer.entry(ref(1, 0, "Hello, \"Shepard\""));
            writer.entry(ref(-2, 1, null));
            writer.entry(ref(3, 2, ""));
            writer.entry(ref(-4, 3, "line\r\nbreak"));
            writer.entry(ref(5, 4, "😀 Жёлтый"));
            writer.end();
            assertEquals("﻿" + HEAD + "1,0,true,\"Hello, \"\"Shepard\"\"\"\r\n-2,1,false,\r\n3,2,true,\r\n"
                    + "-4,3,true,\"line\r\nbreak\"\r\n5,4,true,😀 Жёлтый\r\n", out.toString());
        }

        @Test
        void unpairedSurrogatesCannotBeWritten() throws IOException {
            FormatWriter writer = CsvFormat.writer(new StringWriter());
            for (String text : Arrays.asList("\uD800", "a\uDC00", "\uDE00\uD83D", "x\uD83D")) {
                IOException e = assertThrows(IOException.class, () -> writer.entry(ref(7, 3, text)));
                assertTrue(e.getMessage().startsWith("String 7 (position 3) cannot be written as CSV: it contains the"
                        + " unpaired surrogate U+D"), e.getMessage());
            }
        }
    }

    @Nested
    class Reading {

        @Test
        void readsVersionAndEntries() throws IOException {
            InputFile in = read(HEAD + "100,0,true,Hello\r\n-7,1,false,\r\n101,2,true,Привет\r\n100,3,true,\r\n");
            assertEquals("1.0.4", in.toolVersion);
            assertEquals(Arrays.asList("100@0", "-7@1", "101@2", "100@3"), idsAndPositions(in));
            assertEquals(Arrays.asList("Hello", null, "Привет", ""), texts(in));
        }

        @Test
        void quotedFieldsKeepCommasQuotesAndLineBreaks() throws IOException {
            InputFile in = read(HEAD + "1,0,true,\"a,b\"\r\n2,1,true,\"say \"\"hi\"\"\"\r\n3,2,true,\"x\r\ny\"\r\n"
                    + "4,3,true,\"lf\nonly\"\r\n5,4,true,\"cr\ronly\"\r\n6,5,true,\"\"\r\n7,6,true,\"\"\"\"\r\n"
                    + "\"8\",\"7\",\"true\",\"quoted\"\r\n");
            assertEquals(Arrays.asList("a,b", "say \"hi\"", "x\r\ny", "lf\nonly", "cr\ronly", "", "\"", "quoted"), texts(in));
            assertEquals(7, in.entries.get(7).position);
        }

        @Test
        void allRecordEndsByteOrderMarkAndMissingLastLineEndAreAccepted() throws IOException {
            String crlf = HEAD + "1,0,true,\"multi\r\nline\"\r\n2,1,false,\r\n3,2,true,Жёлтый";
            List<String> expected = Arrays.asList("multi\r\nline", null, "Жёлтый");
            assertEquals(expected, texts(read(crlf)));
            assertEquals(expected, texts(read(crlf + "\r\n")));
            assertEquals(expected, texts(read("﻿" + crlf)));
            assertEquals(Arrays.asList("multi\nline", null, "Жёлтый"), texts(read(crlf.replace("\r\n", "\n"))));
            assertEquals(Arrays.asList("multi\rline", null, "Жёлтый"), texts(read(crlf.replace("\r\n", "\r"))));
        }

        @Test
        void blankLinesAndEmptyRecordsAreSkipped() throws IOException {
            InputFile in = read("\r\n" + HEAD.replace("\r\n", "\r\n\r\n") + "1,0,true,a\r\n,,,\r\n\r\n2,1,true,b\r\n\r\n\r\n");
            assertEquals(Arrays.asList("1@0", "2@1"), idsAndPositions(in));
        }

        @Test
        void excelStyleBooleansPaddingAndSpacesAroundNumbers() throws IOException {
            InputFile in = read("TLKToolVersion,1.0.4,,\r\nid,position,hasText,text\r\n 1 , 0 ,TRUE,  spaced  \r\n2,1,False,\r\n");
            assertEquals(Arrays.asList("1@0", "2@1"), idsAndPositions(in));
            assertEquals(Arrays.asList("  spaced  ", null), texts(in));
        }

        @Test
        void columnsMayBeReorderedRenamedInCaseAndExtended() throws IOException {
            InputFile in = read("TLKToolVersion,1.0.4\r\nText, HASTEXT ,notes,Position,ID\r\nHello,true,note,5,10\r\n,false,,6,-11\r\n");
            assertEquals(Arrays.asList("10@5", "-11@6"), idsAndPositions(in));
            assertEquals(Arrays.asList("Hello", null), texts(in));
        }

        @Test
        void positionsAreTakenFromTheFile() throws IOException {
            InputFile in = read(HEAD + "1,2,true,c\r\n2,0,true,a\r\n3,1,true,b\r\n");
            assertEquals(Arrays.asList("1@2", "2@0", "3@1"), idsAndPositions(in));
        }

        @Test
        void headerOnlyGivesNoEntries() throws IOException {
            assertTrue(read(HEAD).entries.isEmpty());
            assertEquals("2.1", read("TLKToolVersion, 2.1 \r\nid,position,hasText,text").toolVersion);
        }
    }

    @Nested
    class Errors {

        @Test
        void emptyFile() throws IOException {
            assertReadFails("", "Missing version record \"TLKToolVersion,<version>\" as the first record, found an empty file", 1);
            assertReadFails("\r\n\r\n", "found an empty file", 3);
        }

        @ParameterizedTest
        @ValueSource(strings = {"id,position,hasText,text\r\n", "TLKToolVersion\r\n", "TLKToolVersion,\r\n", "Version,1.0.4\r\n"})
        void missingVersionRecord(String content) throws IOException {
            assertReadFails(content, "Missing version record", 1);
        }

        @Test
        void malformedFirstRecord() throws IOException {
            IOException e = assertReadFails("<?xml version=\"1.0\"?>\n<tlkFile/>\n",
                    "Missing version record \"TLKToolVersion,<version>\" as the first record, found a malformed record", 1);
            assertTrue(e.getCause().getMessage().startsWith("Unexpected \" in an unquoted field"), e.getCause().getMessage());
        }

        @Test
        void tooOldOrInvalidVersion() throws IOException {
            Path old = file("TLKToolVersion,1.0.2\r\nid,position,hasText,text\r\n");
            IOException e = assertThrows(IOException.class, () -> CsvFormat.read(old));
            assertEquals("The CSV file was written by TLK tool version 1.0.2; files from versions before 1.0.3 are not"
                    + " supported: " + old, e.getMessage());
            Path invalid = file("TLKToolVersion,one\r\n");
            e = assertThrows(IOException.class, () -> CsvFormat.read(invalid));
            assertEquals("Invalid TLKToolVersion \"one\": " + invalid, e.getMessage());
        }

        @Test
        void missingHeaderRow() throws IOException {
            assertReadFails("TLKToolVersion,1.0.4\r\n\r\n", "Missing header row \"id,position,hasText,text\" after the version record", 3);
        }

        @Test
        void missingOrDuplicateColumn() throws IOException {
            assertReadFails("TLKToolVersion,1.0.4\r\nid,position,text\r\n1,0,a\r\n",
                    "Missing column \"hasText\" in the header row \"id,position,text\" (expected id,position,hasText,text)", 2);
            assertReadFails("TLKToolVersion,1.0.4\r\n1,0,true,a\r\n", "Missing column \"id\"", 2);
            assertReadFails("TLKToolVersion,1.0.4\r\nid,position,hasText,text,ID\r\n", "Column \"id\" occurs twice in the header row", 2);
        }

        @Test
        void tooFewFields() throws IOException {
            assertReadFails(HEAD + "1,0,true,a\r\n2,1,true\r\n", "Expected 4 fields but found 3", 4);
            assertReadFails("TLKToolVersion,1.0.4\r\nid,x,position,hasText,text\r\n1,,0,true\r\n", "Expected 5 fields but found 4", 3);
        }

        @Test
        void invalidValues() throws IOException {
            assertReadFails(HEAD + "x,0,true,a\r\n", "Invalid id \"x\" (not a 32-bit integer)", 3);
            assertReadFails(HEAD + "1,,true,a\r\n", "Invalid position \"\" (not a 32-bit integer)", 3);
            assertReadFails(HEAD + "1,9999999999,true,a\r\n", "Invalid position \"9999999999\"", 3);
            assertReadFails(HEAD + "1,0,yes,a\r\n", "Invalid hasText \"yes\" (must be true or false)", 3);
            assertReadFails(HEAD + "1,0,,a\r\n", "Invalid hasText \"\"", 3);
            assertReadFails(HEAD + "-1,0,false,-1\r\n", "String -1 has hasText=false, so its text must be empty, not \"-1\"", 3);
        }

        @Test
        void malformedQuoting() throws IOException {
            assertReadFails(HEAD + "1,0,true,say \"hi\"\r\n", "Unexpected \" in an unquoted field", 3);
            assertReadFails(HEAD + "1,0,true,\"closed\" too\r\n", "Unexpected \" \" after the closing quote of a field", 3);
            assertReadFails(HEAD + "1,0,true,\"a\"b\r\n", "Unexpected \"b\" after the closing quote", 3);
            assertReadFails(HEAD + "1,0,true,ok\r\n2,1,true,\"never\r\nclosed\r\n",
                    "Quoted field starting at line 4 is not closed (missing \" before the end of the file)", 6);
        }

        @Test
        void lineNumbersCountLineBreaksInsideQuotedFields() throws IOException {
            assertReadFails(HEAD + "1,0,true,\"a\r\nb\nc\rd\"\r\nbad,1,true,x\r\n", "Invalid id \"bad\"", 7);
            assertReadFails(HEAD + "1,0,true,\"a\r\nb\"\r\n2,1,true,\"c\nd\" x\r\n", "after the closing quote", 6);
        }

        @Test
        void invalidUtf8() throws IOException {
            Path f = dir.resolve("cp1251.csv");
            Files.write(f, (HEAD + "1,0,true,Привет\r\n").getBytes("windows-1251"));
            IOException e = assertThrows(IOException.class, () -> CsvFormat.read(f));
            assertTrue(e.getMessage().startsWith("Not a UTF-8 text file ("), e.getMessage());
        }

        @Test
        void utf8WithoutBomIsRead() throws IOException {
            Path f = dir.resolve("plain.csv");
            Files.write(f, (HEAD + "1,0,true,Привет\r\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(java.util.Collections.singletonList("Привет"), texts(CsvFormat.read(f)));
        }
    }
}
