package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.RangeMarker;

import java.util.HashMap;
import java.util.Map;

public class BirdseyeFunction {
    Map<Range, RangeMarker> rangeMarkers = new HashMap<>();
    RangeMarker fullRangeMarker;
    Range fullRange() {
        return new Range(fullRangeMarker);
    }

}
