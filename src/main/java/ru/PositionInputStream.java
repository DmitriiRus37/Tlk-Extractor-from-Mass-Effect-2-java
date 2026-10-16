package ru;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} filter that counts the bytes consumed from the wrapped stream, so the
 * reader can ask for its current byte offset (the Java stand-in for C#
 * {@code BinaryReader.BaseStream.Position}).
 *
 * <p>The position is advanced by {@link #read()}, {@link #read(byte[], int, int)} (and therefore
 * by the inherited {@link #read(byte[])}) and {@link #skip(long)}, and restored by
 * {@link #reset()}. It starts at 0 when the filter is created, whatever the wrapped stream had
 * already consumed. The position-tracking methods are {@code synchronized}.
 *
 * <p>{@link TlkFile} reads TLK files through it so that its error messages can name the byte
 * offset where a file is truncated or corrupt.
 */
public final class PositionInputStream extends FilterInputStream {

    /** Number of bytes read or skipped so far, i.e. the offset of the next byte to be read. */
    private long pos = 0;
    /** Value of {@link #pos} saved by the last {@link #mark(int)}, restored by {@link #reset()}. */
    private long mark = 0;

    /**
     * Wraps {@code in}, starting the position count at 0.
     *
     * @param in the stream to read from
     */
    public PositionInputStream(InputStream in) {
        super(in);
    }

    /**
     * <p>Get the stream position.</p>
     *
     * <p>Eventually, the position will roll over to a negative number.
     * Reading 1 Tb per second, this would occur after approximately three
     * months. Applications should account for this possibility in their
     * design.</p>
     *
     * @return the current stream position.
     */
    public synchronized long getPosition() {
        return pos;
    }

    /**
     * Reads one byte and advances the position by one, unless the end of the stream was reached.
     *
     * @return the byte read, as an {@code int} in {@code 0..255}, or {@code -1} at end of stream
     * @throws IOException if the wrapped stream fails
     */
    @Override
    public synchronized int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            pos += 1;
        }
        return b;
    }

    /**
     * Reads up to {@code len} bytes and advances the position by the number actually read.
     *
     * @param b   buffer to read into
     * @param off index in {@code b} where the first byte is stored
     * @param len maximum number of bytes to read
     * @return the number of bytes read (possibly fewer than {@code len}), or {@code -1} at end of
     *         stream
     * @throws IOException if the wrapped stream fails
     */
    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            pos += n;
        }
        return n;
    }

    /**
     * Skips up to {@code skip} bytes and advances the position by the number actually skipped,
     * which may be fewer than requested.
     *
     * @param skip number of bytes to skip
     * @return the number of bytes actually skipped
     * @throws IOException if the wrapped stream fails or cannot skip
     */
    @Override
    public synchronized long skip(long skip) throws IOException {
        long n = super.skip(skip);
        if (n > 0) {
            pos += n;
        }
        return n;
    }

    /**
     * Marks the current point of the wrapped stream and remembers the current position, so that
     * {@link #reset()} can return to both.
     *
     * @param readlimit number of bytes that may be read before the mark becomes invalid
     */
    @Override
    public synchronized void mark(int readlimit) {
        super.mark(readlimit);
        mark = pos;
    }

    /**
     * Rewinds the wrapped stream to the last mark and restores the position saved with it.
     *
     * @throws IOException if the wrapped stream does not support mark/reset (checked up front,
     *                     because such a reset could otherwise "succeed" and leave the position
     *                     undefined) or if its reset fails
     */
    @Override
    public synchronized void reset() throws IOException {
        /* A call to reset can still succeed if mark is not supported, but the
         * resulting stream position is undefined, so it's not allowed here. */
        if (!markSupported()) {
            throw new IOException("Mark not supported.");
        }
        super.reset();
        pos = mark;
    }

    /**
     * Skips exactly {@code n} bytes, advancing the position by {@code n}, or throws if the stream
     * ends first. Does nothing if {@code n} is zero or negative.
     *
     * <p>Behaves like {@code InputStream.skipNBytes(long)} of Java 12 and later (which it overrides
     * there) and is also available on Java 8 to 11. Bytes that {@link #skip(long)} does not skip
     * are read instead, so it works on streams that cannot skip ahead.
     *
     * @param n number of bytes to skip
     * @throws EOFException if the stream ends before {@code n} bytes were skipped; the position
     *                      then counts the bytes that were skipped
     * @throws IOException  if the wrapped stream fails
     */
    public synchronized void skipNBytes(long n) throws IOException {
        while (n > 0) {
            long skipped = skip(n);
            if (skipped > 0) {
                n -= skipped;
            } else if (read() >= 0) {
                n--;
            } else {
                throw new EOFException("End of stream at position " + pos + ", " + n + " more bytes to skip");
            }
        }
    }

}
