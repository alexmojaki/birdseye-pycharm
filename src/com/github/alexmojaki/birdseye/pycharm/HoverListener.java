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

/**
 * This class watches the mouse for hovering over and clicking on nodes in a call.
 */
public class HoverListener extends EditorMouseMotionAdapter implements EditorMouseListener {
    static final HoverListener INSTANCE = new HoverListener();

    /**
     * Highlights currentNode
     */
    private HideableRangeHighlighter rangeHighlighter;

    /**
     * The node currently being hovered over
     */
    private Call.Node currentNode = null;

    /**
     * Handle hovering over nodes
     */
    @Override
    public void mouseMoved(EditorMouseEvent e) {
        Editor editor = e.getEditor();
        Project project = editor.getProject();
        if (project == null) {
            return;
        }

        MouseEvent mouseEvent = e.getMouseEvent();

        // Without this, hovering over the gutter can trigger events
        if (mouseEvent.getSource() != editor.getContentComponent()) {
            return;
        }

        // When the user hovers the mouse over a character,
        // it's generally not exactly at one document offset. Rather it's
        // between two offsets. We want to find the lower of these two offsets,
        // i.e. the one to the left of the cursor. Then the cursor is between
        // offset and offset+1. See Call.nodeAtPosition (called below)
        Point point = new Point(mouseEvent.getPoint());
        LogicalPosition pos = editor.xyToLogicalPosition(point);
        int offset = editor.logicalPositionToOffset(pos);
        Point offsetPoint = editor.offsetToXY(offset);
        if (offsetPoint.x > point.x) {
            offset -= 1;
        }

        // Find the node being hovered over, if there is one
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
            // We're hovering over the same node as before (possibly none),
            // no need to do anything
            return;
        }

        currentNode = node;

        // Stop highlighting the previously hovered node
        if (rangeHighlighter != null) {
            rangeHighlighter.hide();
            rangeHighlighter = null;
        }

        Call.NodeValue nodeValue;
        if (node == null
                || node.isRangeInvalid()
                || (nodeValue = node.value()) == null
                || nodeValue.isNotInteresting()
                ) {

            // Highlight nothing
            HoverValueEditorLinePainter.repr = "";
            return;
        }

        // Show repr of hovered value at end of line
        HoverValueEditorLinePainter.moveExtensionToBeginning();
        HoverValueEditorLinePainter.repr = nodeValue.repr();
        HoverValueEditorLinePainter.currentFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        HoverValueEditorLinePainter.currentProjectHash = project.getLocationHash();
        HoverValueEditorLinePainter.currentLineNumber = pos.line;

        // Highlight text of node
        TextAttributes attributes = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(DiffColors.DIFF_MODIFIED);
        rangeHighlighter = node.addRangeHighlighter(attributes);
    }

    /**
     * Handle clicking on nodes
     */
    @Override
    public void mouseClicked(EditorMouseEvent e) {
        if (currentNode == null || currentNode.isRangeInvalid()) {
            return;
        }
        Call.NodeValue value = currentNode.value();
        if (value == null || value.isNotInteresting()) {
            return;
        }

        Call call = currentNode.call();
        MyProjectComponent component = MyProjectComponent.getInstance(call.project);
        if (component.currentCall() != call) {
            return;
        }

        call.panel.toggleSelectedNode(currentNode);
    }

    // Other methods of the interface EditorMouseListener

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