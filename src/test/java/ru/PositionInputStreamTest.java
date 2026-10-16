package ru;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionInputStreamTest {

    private static PositionInputStream stream(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return new PositionInputStream(new ByteArrayInputStream(data));
    }

    /** A stream without mark/reset support (InputStream's default). */
    private static InputStream unmarkable(final int length) {
        return new InputStream() {
            private int next;

            @Override
            public int read() {
                return next < length ? next++ : -1;
            }
        };
    }

    @Test
    void startsAtZero() {
        assertEquals(0, stream(10).getPosition());
        assertEquals(0, stream(0).getPosition());
    }

    @Test
    void startsAtZeroEvenIfTheWrappedStreamWasAlreadyRead() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{10, 11, 12});
        assertEquals(10, in.read());
        PositionInputStream p = new PositionInputStream(in);
        assertEquals(0, p.getPosition());
        assertEquals(11, p.read());
        assertEquals(1, p.getPosition());
    }

    @Test
    void singleByteReadsAdvanceByOne() throws IOException {
        PositionInputStream p = stream(3);
        assertEquals(0, p.read());
        assertEquals(1, p.getPosition());
        assertEquals(1, p.read());
        assertEquals(2, p.read());
        assertEquals(3, p.getPosition());
    }

    @Test
    void readReturnsUnsignedValues() throws IOException {
        PositionInputStream p = new PositionInputStream(new ByteArrayInputStream(new byte[]{(byte) 0xff, (byte) 0x80}));
        assertEquals(255, p.read());
        assertEquals(128, p.read());
    }

    @Test
    void endOfStreamDoesNotAdvance() throws IOException {
        PositionInputStream p = stream(1);
        assertEquals(0, p.read());
        assertEquals(-1, p.read());
        assertEquals(-1, p.read());
        assertEquals(1, p.getPosition());
        assertEquals(-1, p.read(new byte[4], 0, 4));
        assertEquals(-1, p.read(new byte[4]));
        assertEquals(1, p.getPosition());
    }

    @Test
    void emptyStream() throws IOException {
        PositionInputStream p = stream(0);
        assertEquals(-1, p.read());
        assertEquals(-1, p.read(new byte[1]));
        assertEquals(0, p.skip(10));
        assertEquals(0, p.getPosition());
    }

    @Test
    void bulkReadsAdvanceByBytesActuallyRead() throws IOException {
        PositionInputStream p = stream(10);
        byte[] buffer = new byte[6];
        assertEquals(4, p.read(buffer, 2, 4));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 2, 3}, buffer);
        assertEquals(4, p.getPosition());

        // the inherited read(byte[]) goes through read(byte[], int, int)
        assertEquals(6, p.read(buffer));
        assertEquals(10, p.getPosition());
    }

    @Test
    void shortReadAtTheEndAdvancesByTheShortCount() throws IOException {
        PositionInputStream p = stream(5);
        assertEquals(5, p.read(new byte[100], 0, 100));
        assertEquals(5, p.getPosition());
    }

    @Test
    void zeroLengthReadDoesNotAdvance() throws IOException {
        PositionInputStream p = stream(5);
        assertEquals(0, p.read(new byte[0], 0, 0));
        assertEquals(0, p.read(new byte[3], 1, 0));
        assertEquals(0, p.getPosition());
    }

    @Test
    void invalidBufferRangeIsRejected() {
        PositionInputStream p = stream(5);
        assertThrows(IndexOutOfBoundsException.class, () -> p.read(new byte[2], 1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> p.read(new byte[2], -1, 1));
        assertThrows(NullPointerException.class, () -> p.read(null, 0, 1));
        assertEquals(0, p.getPosition());
    }

    @Test
    void skipAdvancesByBytesActuallySkipped() throws IOException {
        PositionInputStream p = stream(10);
        assertEquals(3, p.skip(3));
        assertEquals(3, p.getPosition());
        assertEquals(3, p.read());
        // skipping past the end is cut short
        assertEquals(6, p.skip(100));
        assertEquals(10, p.getPosition());
        assertEquals(0, p.skip(1));
        assertEquals(10, p.getPosition());
    }

    @Test
    void zeroAndNegativeSkipsDoNotMove() throws IOException {
        PositionInputStream p = stream(10);
        p.skip(2);
        assertEquals(0, p.skip(0));
        assertEquals(0, p.skip(-5));
        assertEquals(2, p.getPosition());
        assertEquals(2, p.read());
    }

    @Test
    void positionMatchesBytesConsumedAcrossMixedCalls() throws IOException {
        PositionInputStream p = stream(100);
        p.read();
        p.read(new byte[7]);
        p.skip(10);
        p.read(new byte[5], 0, 2);
        assertEquals(20, p.getPosition());
        assertEquals(20, p.read());
    }

    @Test
    void markAndResetRestorePosition() throws IOException {
        PositionInputStream p = stream(20);
        assertTrue(p.markSupported());
        p.skip(5);
        p.mark(100);
        p.read(new byte[4]);
        p.skip(3);
        assertEquals(12, p.getPosition());
        p.reset();
        assertEquals(5, p.getPosition());
        assertEquals(5, p.read());
        // reset again returns to the same mark
        p.reset();
        assertEquals(5, p.getPosition());
    }

    @Test
    void resetWithoutMarkGoesBackToStart() throws IOException {
        PositionInputStream p = stream(10);
        p.skip(4);
        p.reset();
        assertEquals(0, p.getPosition());
        assertEquals(0, p.read());
    }

    @Test
    void resetIsRejectedWhenMarkIsNotSupported() throws IOException {
        PositionInputStream p = new PositionInputStream(unmarkable(10));
        assertFalse(p.markSupported());
        p.mark(10);
        p.read();
        IOException e = assertThrows(IOException.class, p::reset);
        assertEquals("Mark not supported.", e.getMessage());
        assertEquals(1, p.getPosition());
    }

    @Test
    void closeClosesTheWrappedStream() throws IOException {
        final boolean[] closed = {false};
        PositionInputStream p = new PositionInputStream(new ByteArrayInputStream(new byte[1]) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        });
        p.close();
        assertTrue(closed[0]);
    }

    @Test
    void availableIsPassedThrough() throws IOException {
        PositionInputStream p = stream(10);
        p.skip(4);
        assertEquals(6, p.available());
    }

    @Test
    void skipNBytesSkipsExactlyAndAdvancesPosition() throws IOException {
        PositionInputStream p = stream(10);
        p.skipNBytes(3);
        assertEquals(3, p.getPosition());
        assertEquals(3, p.read());
        p.skipNBytes(0L);
        p.skipNBytes(-4);
        assertEquals(4, p.getPosition());
    }

    @Test
    void skipNBytesReadsWhatTheStreamDoesNotSkip() throws IOException {
        // an InputStream whose skip() always returns 0
        PositionInputStream p = new PositionInputStream(new InputStream() {
            private int next;

            @Override
            public int read() {
                return next < 10 ? next++ : -1;
            }

            @Override
            public long skip(long n) {
                return 0;
            }
        });
        p.skipNBytes(4);
        assertEquals(4, p.getPosition());
        assertEquals(4, p.read());
    }

    @Test
    void skipNBytesPastTheEndIsEof() {
        PositionInputStream p = stream(5);
        EOFException e = assertThrows(EOFException.class, () -> p.skipNBytes(8));
        assertEquals(5, p.getPosition());
        assertTrue(e.getMessage().contains("3 more bytes"), e.getMessage());
    }
}
