package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BirdseyeFunction {

    static final Key<Range> ORIGINAL_RANGE = Key.create("ORIGINAL_RANGE");
    Map<Range, RangeMarker> rangeMarkers = new HashMap<>();
    RangeMarker fullRangeMarker;

    BirdseyeFunction(PsiElement psiFunction, List<Range> ranges, Document document) {
        int startOffset = Utils.getFunctionStart(psiFunction);

        for (Range range : ranges) {
            RangeMarker rangeMarker = document.createRangeMarker(
                    range.start + startOffset,
                    range.end + startOffset,
                    true);
            rangeMarker.putUserData(ORIGINAL_RANGE, range);
            rangeMarkers.put(range, rangeMarker);
        }

        fullRangeMarker = document.createRangeMarker(
                startOffset,
                psiFunction.getTextRange().getEndOffset());
    }


    Range fullRange() {
        return new Range(fullRangeMarker);
    }

}
