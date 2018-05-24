package com.github.alexmojaki.birdseye.pycharm;

import com.google.common.collect.Lists;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.github.alexmojaki.birdseye.pycharm.Utils.tag;

public class CallPanel extends JBPanel {
    private final DefaultTreeModel inspector = new DefaultTreeModel(new InspectorTreeNode.Root());
    private final Tree tree = new Tree(inspector);
    final List<Call.Node> selectedNodes = new ArrayList<>();
    private final MultiMap<Integer, List<Integer>> openPaths = MultiMap.createSet();
    private CardLayout cardLayout = new CardLayout();
    private JBPanel cardPanel = new JBPanel(cardLayout);

    CallPanel(Call call) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        String information = Arrays.stream(new String[][]{
                {"Arguments", call.meta.argumentsList()},
                {"Start time", call.meta.startTime()},
                {"Result", tag("pre", call.meta.longResult())},
        }).map(a -> tag("b", a[0] + ": ") + a[1]
        ).collect(Collectors.joining("\n<br>\n"));
        JBLabel label = new JBLabel(tag("html",
                information));
        add(label);

        add(cardPanel);
        cardPanel.add(new JBLabel("Click on an expression in your code to inspect it here"), "explanation");

        JBPanel treePanel = new JBPanel<>(new BorderLayout());
        treePanel.add(tree);
        cardPanel.add(treePanel, "tree");
        tree.expandRow(0);
        tree.setRootVisible(false);
        tree.setCellRenderer(new InspectorTreeCellRenderer());

        setAlignmentX(Component.LEFT_ALIGNMENT);
        cardPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        tree.addTreeExpansionListener(new TreeExpansionListener() {

            private void processPath(TreeExpansionEvent event, BiConsumer<Integer, List<Integer>> func) {
                List<Integer> path = Arrays.stream(event.getPath().getPath())
                        .map(n -> ((InspectorTreeNode) n).index)
                        .collect(Collectors.toList());
                synchronized (tree) {
                    func.accept(path.get(1), path.subList(2, path.size()));
                }
            }

            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                processPath(event, openPaths::putValue);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                processPath(event, openPaths::remove);
            }
        });
    }

    void toggleSelectedNode(Call.Node node) {
        if (node.selectedHighlighter != null) {
            node.selectedHighlighter.hide();
            node.selectedHighlighter = null;
        } else {
            TextAttributes attributes = new TextAttributes();
            attributes.setEffectColor(JBColor.blue);
            attributes.setEffectType(EffectType.ROUNDED_BOX);
            node.selectedHighlighter = node.addRangeHighlighter(attributes);
        }

        DefaultMutableTreeNode root = root();
        DefaultMutableTreeNode treeNode = node.inspectorTreeNode();
        if (selectedNodes.contains(node)) {
            selectedNodes.remove(node);
            treeNode.removeFromParent();
        } else {
            if (treeNode == null) {
                return;
            }
            inspector.insertNodeInto(treeNode, root, 0);
            selectedNodes.add(0, node);
        }
        cardLayout.show(cardPanel, selectedNodes.isEmpty() ? "explanation" : "tree");
        updateValues();
    }

    private DefaultMutableTreeNode root() {
        return (DefaultMutableTreeNode) inspector.getRoot();
    }

    public void updateValues() {
        DefaultMutableTreeNode root = root();
        root.removeAllChildren();
        for (Call.Node node : Lists.reverse(selectedNodes)) {
            inspector.insertNodeInto(node.freshInspectorTreeNode(), root, 0);
        }
        inspector.nodeStructureChanged(root);

        List<TreePath> paths = new ArrayList<>();
        synchronized (tree) {
            for (int i = 0; i < root.getChildCount(); i++) {
                InspectorTreeNode valueRoot = (InspectorTreeNode) root.getChildAt(i);
                for (List<Integer> path : openPaths.get(valueRoot.index)) {
                    paths.add(getIndexPath(valueRoot, path));
                }
            }
        }

        for (TreePath path : paths) {
            tree.expandPath(path);
        }
    }

    private TreePath getIndexPath(TreeNode root, List<Integer> path) {
        for (Integer index : path) {
            if (index >= root.getChildCount()) {
                return null;
            }
            root = root.getChildAt(index);
        }
        return new TreePath(((DefaultMutableTreeNode) root).getPath());

    }

    private static class InspectorTreeCellRenderer extends ColoredTreeCellRenderer {

        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            InspectorTreeNode node = (InspectorTreeNode) value;
            append(node.label + " = ");
            node.render(this);
        }
    }

}
