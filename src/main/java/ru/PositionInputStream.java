package ru;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} filter that counts the bytes consumed from the wrapped stream, so the
 * reader can ask for its current byte offset (the Java stand-in for C#
 * {@code BinaryReader.BaseStream.Position}, used by {@link TlkFile}).
 *
 * <p>The position is advanced by {@link #read()}, {@link #read(byte[], int, int)} (and therefore
 * by the inherited {@link #read(byte[])}) and {@link #skip(long)}, and restored by
 * {@link #reset()}. It starts at 0 when the filter is created, whatever the wrapped stream had
 * already consumed. The position-tracking methods are {@code synchronized}.
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
     * Meant to skip exactly {@code count} bytes, but does nothing.
     *
     * <p>Note: the body is empty, so no bytes are skipped and the position does not change. This
     * is an overload, not an override, of {@link InputStream#skipNBytes(long)}: calls with an
     * {@code int} argument end up here, calls with a {@code long} still reach the inherited
     * method, which does skip (through {@link #skip(long)}). Nothing in this project calls it.
     *
     * @param count number of bytes that should have been skipped (ignored)
     */
    public void skipNBytes(int count) {

    }

}
