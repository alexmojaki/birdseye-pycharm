package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.util.Key;
import com.jetbrains.python.psi.PyFunction;

import java.util.HashMap;
import java.util.Map;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

/**
 * This class is created when the user clicks on an eye icon on the left gutter,
 * showing a table of calls to the corresponding function. It manages data
 * common to those calls, and uses range markers to handle changes to the document made by the user between
 * retrieving that list of calls and retrieving a specific call.
 *
 * The name of the class instead of just 'Function' is to distinguish from the functional interface
 * provided by Java and libraries.
 */
public class BirdseyeFunction {

    static final Key<Range> ORIGINAL_RANGE = Key.create("ORIGINAL_RANGE");

    // Each Range in these maps corresponds to one or more nodes in the Python AST
    // that we need to keep track of in the document. The Range comes from the
    // Python side and is relative to the start of the function.
    Map<Range, RangeMarker> rangeMarkers = new HashMap<>();
    Map<Range, RangeMarker> loopRangeMarkers = new HashMap<>();

    // This keeps track of the start of the function. It allows identifying
    // different versions of the same function as it changes over time but
    // occupies the same space in the document.
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
