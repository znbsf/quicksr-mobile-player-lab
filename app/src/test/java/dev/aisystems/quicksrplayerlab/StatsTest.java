package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class StatsTest {
    @Test
    public void nearestRankMatchesFrozenThirtyRunContract() {
        List<Double> values = new ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            values.add((double) i);
        }
        assertEquals(15.0, Stats.nearestRankPercentile(values, 0.50), 0.0);
        assertEquals(29.0, Stats.nearestRankPercentile(values, 0.95), 0.0);
    }

    @Test
    public void rejectsEmptyInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Stats.nearestRankPercentile(Collections.emptyList(), 0.50)
        );
    }
}
