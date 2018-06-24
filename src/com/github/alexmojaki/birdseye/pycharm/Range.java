package com.github.alexmojaki.birdseye.pycharm;

import java.util.Objects;

/**
 * A simple struct (start, end) meant to be populated by JSON from birdseye.
 * The numbers represent text offsets.
 */
public class Range {
    final int start;
    final int end;

    Range(int start, int end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Range range = (Range) o;
        return start == range.start &&
                end == range.end;
    }

    @Override
    public String toString() {
        return "Range{" +
                "start=" + start +
                ", end=" + end +
                '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }
}
