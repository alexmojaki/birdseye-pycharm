package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.RangeMarker;

import java.util.Objects;

public class Range {
    int start;
    int end;

    Range(int start, int end) {
        this.start = start;
        this.end = end;
    }

    Range(RangeMarker rangeMarker) {
        this(rangeMarker.getStartOffset(), rangeMarker.getEndOffset());
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
