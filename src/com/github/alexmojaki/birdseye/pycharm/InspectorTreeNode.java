package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

public abstract class InspectorTreeNode extends DefaultMutableTreeNode {
    Call.Node node;
    String label;

    abstract void render(ColoredTreeCellRenderer renderer);

    static class Expression extends InspectorTreeNode {
        Icon icon;
        String typeName;
        String repr;

        Expression(Icon icon, String typeName, String repr) {
            this.icon = icon;
            this.typeName = typeName;
            this.repr = repr;
        }

        @Override
        void render(ColoredTreeCellRenderer renderer) {
            renderer.append("{" + typeName + "} ", SimpleTextAttributes.GRAY_ATTRIBUTES);
            renderer.append(repr);
            renderer.setIcon(icon);
        }
    }

    static class Statement extends InspectorTreeNode {

        @Override
        void render(ColoredTreeCellRenderer renderer) {
            renderer.append("statement ran fine", SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES);
            renderer.setIcon(AllIcons.Debugger.ThreadStates.Idle);
        }
    }

    static class Exception extends InspectorTreeNode {

        Exception(String message) {
            this.message = message;
        }

        String message;

        @Override
        void render(ColoredTreeCellRenderer renderer) {
            renderer.append(message, SimpleTextAttributes.ERROR_ATTRIBUTES);
            renderer.setIcon(AllIcons.General.Error);
        }
    }

    static class Len extends InspectorTreeNode {

        int len;

        Len(int len) {
            label = "len()";
            this.len = len;
        }

        @Override
        void render(ColoredTreeCellRenderer renderer) {
            renderer.append(String.valueOf(len));
        }
    }

    /**
     * The always invisible root of the inspector tree, doesn't correspond to a value.
     */
    static class Root extends InspectorTreeNode {

        @Override
        void render(ColoredTreeCellRenderer renderer) {
        }
    }

    /**
     * Used when navigating through a loop leads to an inspected node not having
     * a value at that time.
     */
    static class NotEvaluated extends InspectorTreeNode {

        // This is the only time we have to provide a label in the constructor
        // because this isn't created in the NodeValue.treeNode method
        NotEvaluated(String label) {
            this.label = label;
        }

        @Override
        void render(ColoredTreeCellRenderer renderer) {
            renderer.append("not evaluated", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            renderer.setIcon(AllIcons.Actions.Clear);
        }
    }

}
