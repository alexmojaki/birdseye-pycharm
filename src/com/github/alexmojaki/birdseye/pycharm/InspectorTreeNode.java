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

    static class Root extends InspectorTreeNode {

        @Override
        void render(ColoredTreeCellRenderer renderer) {
        }
    }


}
