package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

/**
 * This class addresses some deficiencies in the RangeHighlighter class:
 * - It can be temporarily hidden and later restored
 * - It is shown in all editors (for the correct project at least) of the same document
 */
public class HideableRangeHighlighter {

    /** The node being highlighted */
    private final Call.Node node;
    private final TextAttributes attributes;

    /** The highlighters for each editor of the document */
    private List<RangeHighlighter> highlighters = new ArrayList<>();

    HideableRangeHighlighter(Call.Node node, TextAttributes attributes) {
        this.node = node;
        this.attributes = attributes;
        show();
    }

    /** Create a RangeHighlighter for each editor */
    void show() {
        if (!highlighters.isEmpty()) {  // i.e. if it's already showing
            return;
        }

        Project project = node.call().project;
        for (Editor editor : activeEditors(project)) {
            addFor(editor);
        }
    }

    /**
     * Add a single normal RangeHighlighter to this editor
     */
    void addFor(Editor editor) {
        RangeMarker rm = node.rangeMarker();

        if (!rm.isValid()) {
            return;
        }

        Call call = node.call();
        Project project = call.project;

        if (!(call.document().equals(editor.getDocument()) &&
                project.equals(editor.getProject()))) {
            return;
        }

        DumbService.getInstance(project).smartInvokeLater(() -> {
            RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
                    rm.getStartOffset(),
                    rm.getEndOffset(),
                    1000000,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE);
            highlighters.add(highlighter);
        });
    }

    /** Destroy all the RangeHighlighters */
    void hide() {
        Project project = node.call().project;

        // Happens if the call has been cleared
        if (project == null) {
            return;
        }

        DumbService.getInstance(project).smartInvokeLater(() -> {
            List<Editor> editors = activeEditors(project);
            for (RangeHighlighter highlighter : highlighters) {
                highlighter.dispose();
                for (Editor editor : editors) {
                    editor.getMarkupModel().removeHighlighter(highlighter);
                }

            }
            highlighters.clear();
        });

    }


}
