package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.diff.DiffColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionAdapter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;

import java.awt.*;
import java.awt.event.MouseEvent;

public class HoverListener extends EditorMouseMotionAdapter implements EditorMouseListener {
    static final HoverListener INSTANCE = new HoverListener();

    private HideableRangeHighlighter rangeHighlighter;
    private Call.Node currentNode = null;

    @Override
    public void mouseMoved(EditorMouseEvent e) {
        Editor editor = e.getEditor();
        Project project = editor.getProject();
        if (project == null) {
            return;
        }

        MouseEvent mouseEvent = e.getMouseEvent();
        if (mouseEvent.getSource() != editor.getContentComponent()) {
            return;
        }

        Point point = new Point(mouseEvent.getPoint());
        LogicalPosition pos = editor.xyToLogicalPosition(point);
        int offset = editor.logicalPositionToOffset(pos);
        Point offsetPoint = editor.offsetToXY(offset);
        if (offsetPoint.x > point.x) {
            offset -= 1;
        }

        Call.Node node = null;
        for (Call call : MyProjectComponent.getInstance(project).activeCalls()) {
            if (!call.document().equals(editor.getDocument())) {
                continue;
            }

            node = call.nodeAtPosition(offset);
            if (node != null) {
                break;
            }
        }

        if (node == currentNode) {
            return;
        }
        currentNode = node;

        if (rangeHighlighter != null) {
            rangeHighlighter.hide();
        }

        if (node == null || node.isRangeInvalid()) {
            HoverValueEditorLinePainter.repr = "";
            return;
        }

        Call.NodeValue nodeValue = node.value();
        if (nodeValue == null || nodeValue.isNotInteresting()) {
            return;
        }
        HoverValueEditorLinePainter.moveExtensionToBeginning();
        HoverValueEditorLinePainter.repr = nodeValue.repr();
        HoverValueEditorLinePainter.currentFile = FileDocumentManager.getInstance().getFile(e.getEditor().getDocument());
        HoverValueEditorLinePainter.currentProjectHash = project.getLocationHash();
        HoverValueEditorLinePainter.currentLineNumber = pos.line;

        TextAttributes attributes = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(DiffColors.DIFF_MODIFIED);

        rangeHighlighter = node.addRangeHighlighter(attributes);
    }

    @Override
    public void mouseClicked(EditorMouseEvent e) {
        if (currentNode == null || currentNode.isRangeInvalid()) {
            return;
        }
        Call.NodeValue value = currentNode.value();
        if (value == null || value.isNotInteresting()) {
            return;
        }

        currentNode.call().panel.toggleSelectedNode(currentNode);
    }

    @Override
    public void mousePressed(EditorMouseEvent e) {
    }

    @Override
    public void mouseReleased(EditorMouseEvent e) {
    }

    @Override
    public void mouseEntered(EditorMouseEvent e) {
    }

    @Override
    public void mouseExited(EditorMouseEvent e) {
    }
}