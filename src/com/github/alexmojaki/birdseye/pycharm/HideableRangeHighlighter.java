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

public class HideableRangeHighlighter {

    private final Call.Node node;
    private final TextAttributes attributes;
    private List<RangeHighlighter> highlighters = new ArrayList<>();

    HideableRangeHighlighter(Call.Node node, TextAttributes attributes) {
        this.node = node;
        this.attributes = attributes;
        show();
    }

    void show() {
        if (!highlighters.isEmpty()) {
            return;
        }
        Project project = node.call().project;
        for (Editor editor : Utils.activeEditors(project)) {
            addFor(editor);
        }
    }

    void addFor(Editor editor) {
        Call call = node.call();
        Project project = call.project;

        if (!(call.document.equals(editor.getDocument()) &&
                project.equals(editor.getProject()))) {
            return;
        }

        DumbService.getInstance(project).smartInvokeLater(() -> {
            RangeMarker rm = node.rangeMarker;
            RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
                    rm.getStartOffset(),
                    rm.getEndOffset(),
                    1000000,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE);
            highlighters.add(highlighter);
        });
    }

    void hide() {
        Project project = node.call().project;
        DumbService.getInstance(project).smartInvokeLater(() -> {
            List<Editor> editors = Utils.activeEditors(project);
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
