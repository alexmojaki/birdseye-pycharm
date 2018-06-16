package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.util.Key;
import com.jetbrains.python.psi.PyFunction;

import java.util.HashMap;
import java.util.Map;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

public class BirdseyeFunction {

    static final Key<Range> ORIGINAL_RANGE = Key.create("ORIGINAL_RANGE");
    Map<Range, RangeMarker> rangeMarkers = new HashMap<>();
    Map<Range, RangeMarker> loopRangeMarkers = new HashMap<>();
    RangeMarker startRangeMarker;
    String originalText;
    DocumentEx document;

    BirdseyeFunction(PyFunction psiFunction, ApiClient.CallsByHashResponse response) {
        document = psiElementDocument(psiFunction);

        int startOffset = getFunctionStart(psiFunction);

        for (Range range : response.ranges) {
            RangeMarker rangeMarker = createRangeMarker(document, startOffset, range);
            rangeMarker.putUserData(ORIGINAL_RANGE, range);
            rangeMarkers.put(range, rangeMarker);
        }

        for (Range range : response.loop_ranges) {
            RangeMarker rangeMarker = createRangeMarker(document, startOffset, range);
            loopRangeMarkers.put(range, rangeMarker);
        }

        startRangeMarker = document.createRangeMarker(
                startOffset,
                startOffset,
                true);

        originalText = getFunctionText(psiFunction);
    }

    private RangeMarker createRangeMarker(Document document, int startOffset, Range range) {
        return document.createRangeMarker(
                range.start + startOffset,
                range.end + startOffset,
                true);
    }

}
