package ru;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressReporterTest {

    @Test
    void reportsEachPercentOnce() {
        List<Double> reported = new ArrayList<>();
        ProgressReporter p = new ProgressReporter(reported::add, 1000);
        for (int i = 0; i < 1000; i++) {
            p.step();
        }
        p.finish();
        // 0 %, 1 %, ..., 100 %: the first step of 1000 is still 0 %
        assertEquals(101, reported.size());
        for (int i = 0; i <= 100; i++) {
            assertEquals(i / 100.0, reported.get(i), 0.0);
        }
    }

    @Test
    void fewStepsReportEveryStep() {
        List<Double> reported = new ArrayList<>();
        ProgressReporter p = new ProgressReporter(reported::add, 3);
        p.step();
        p.step();
        p.step();
        assertEquals(Arrays.asList(0.33, 0.66, 1.0), reported);
    }

    @Test
    void finishCompletesAnUnfinishedOrEmptyJob() {
        List<Double> reported = new ArrayList<>();
        new ProgressReporter(reported::add, 0).finish();
        assertEquals(Collections.singletonList(1.0), reported);

        reported.clear();
        ProgressReporter p = new ProgressReporter(reported::add, 4);
        p.step();
        p.finish();
        p.finish();
        assertEquals(Arrays.asList(0.25, 1.0), reported);
    }

    @Test
    void extraStepsStayAtOne() {
        List<Double> reported = new ArrayList<>();
        ProgressReporter p = new ProgressReporter(reported::add, 1);
        p.step();
        p.step();
        assertEquals(Collections.singletonList(1.0), reported);
    }

    @Test
    void nullListenerIsAllowed() {
        ProgressReporter p = new ProgressReporter(null, 2);
        p.step();
        p.finish();
    }
}
