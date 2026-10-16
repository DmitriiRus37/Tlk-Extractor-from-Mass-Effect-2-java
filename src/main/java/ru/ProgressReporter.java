package ru;

import java.util.function.DoubleConsumer;

/**
 * Turns "one more item done" steps into calls of a progress listener with the completed
 * fraction, at most once per whole percent, so that a long loop does not flood a GUI progress
 * bar with updates.
 *
 * <p>Used by the conversions in {@link TlkFile} and {@code HuffmanCompression}; the listener (for
 * example the GUI's progress bar) receives values from {@code 0.0} to {@code 1.0}.
 */
final class ProgressReporter {

    /** Receives the completed fraction; {@code null} if nobody is interested. */
    private final DoubleConsumer listener;
    /** Number of steps that make up the whole job. */
    private final int total;
    /** Number of steps done so far. */
    private int done;
    /** Percentage last passed to {@link #listener}, or {@code -1} before the first report. */
    private int lastPercent = -1;

    /**
     * Creates a reporter for a job of {@code total} steps.
     *
     * @param listener receives the completed fraction ({@code 0.0} to {@code 1.0}); may be
     *                 {@code null}, in which case nothing is reported
     * @param total    number of steps; {@code 0} means the job is complete as soon as
     *                 {@link #finish()} is called
     */
    ProgressReporter(DoubleConsumer listener, int total) {
        this.listener = listener;
        this.total = total;
    }

    /** Records one more completed step and reports the new fraction if the percentage changed. */
    void step() {
        done++;
        report();
    }

    /** Marks the whole job as done, reporting {@code 1.0} unless that was already reported. */
    void finish() {
        done = total;
        report();
    }

    /** Passes the completed percentage, as a fraction, to the listener if it changed. */
    private void report() {
        if (listener == null) {
            return;
        }
        int percent = total <= 0 ? 100 : (int) (100L * Math.min(done, total) / total);
        if (percent != lastPercent) {
            lastPercent = percent;
            listener.accept(percent / 100.0);
        }
    }
}
