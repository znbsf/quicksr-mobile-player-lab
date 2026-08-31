package dev.aisystems.quicksrplayerlab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Stats {
    private Stats() {
    }

    static double nearestRankPercentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (!(percentile > 0.0 && percentile <= 1.0)) {
            throw new IllegalArgumentException("percentile must be in (0, 1]");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
