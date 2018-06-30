package com.github.alexmojaki.birdseye.pycharm;

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
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.alexmojaki.birdseye.pycharm.Utils.mapToList;
import static com.github.alexmojaki.birdseye.pycharm.Utils.tag;
import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

/**
 * This is the panel shown for a single call. Its main feature is the inspector, a
 * tree of nodes selected for inspection.
 */
public class CallPanel extends JBPanel {
    private final DefaultTreeModel model = new DefaultTreeModel(new InspectorTreeNode.Root());
    private final Tree tree = new Tree(model);
    final Map<Call.Node, HideableRangeHighlighter> selectedNodes = new LinkedHashMap<>();

    /**
     * This tracks the paths that are open in the inspector, i.e. values expanded, however
     * deep down in the tree. The keys are the nodes being inspected, the 'roots' of the
     * tree. The values are lists of labels. So if the user has selected an expression 'a'
     * in the code, and is looking at the attribute a.b.c, then 'a' is a key and ['b', 'c']
     * is a value. When the user steps through a loop and the values and thus the structure
     * of the tree change, this is used to open the paths in the newly created tree nodes
     * (if they still exist).
     */
    private final MultiMap<Call.Node, List<String>> openPaths = MultiMap.createSet();

    // This either shows the inspector tree, or when it's empty, an explanation of what to do
    private CardLayout cardLayout = new CardLayout();
    private JBPanel cardPanel = new JBPanel(cardLayout);

    // True while reopenPaths is running
    private boolean reopening = false;

    CallPanel(Call call) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        // Basic metadata about the call
        String information = Arrays.stream(new String[][]{
                {"Arguments", call.meta.argumentsList()},
                {"Start time", call.meta.startTime()},
                {"Result", tag("pre", escapeHtml(call.meta.longResult()))},
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

        // Expand the actual root node and hide it, giving the impression
        // of many roots, one for each value
        tree.expandRow(0);
        tree.setRootVisible(false);

        tree.setCellRenderer(new InspectorTreeCellRenderer());

        setAlignmentX(Component.LEFT_ALIGNMENT);
        cardPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Tracking expanded nodes in openPaths
        tree.addTreeExpansionListener(new TreeExpansionListener() {

            private void processPath(TreeExpansionEvent event, BiConsumer<Call.Node, List<String>> func) {
                Object[] treeNodes = event.getPath().getPath();
                List<String> path = mapToList(
                        treeNodes,
                        n -> ((InspectorTreeNode) n).label);
                // treeNodes[0] is the invisible root
                // treeNodes[1] is the 'root', the node being inspected
                // treeNodes[2], treeNodes[3], etc. are the inner values,
                // e.g. attributes, and their labels are the ones we care about.
                Call.Node node = ((InspectorTreeNode) treeNodes[1]).node;
                func.accept(node, path.subList(2, path.size()));
            }

            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                // Only do something if this is the result of the user expanding a single node
                if (!reopening) {
                    processPath(event, openPaths::putValue);
                    reopenPaths();
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                processPath(event, openPaths::remove);
            }
        });

        // Allowing deleting (unselecting) nodes from the inspector
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE ||
                        e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    Call.Node node = ((InspectorTreeNode) tree.getLastSelectedPathComponent()).node;
                    if (node != null && selectedNodes.containsKey(node)) {
                        toggleSelectedNode(node);
                    }
                }
            }
        });
    }

    /**
     * Select or unselect a node for inspection.
     */
    void toggleSelectedNode(Call.Node node) {
        DefaultMutableTreeNode root = root();
        DefaultMutableTreeNode treeNode = node.inspectorTreeNode();
        if (selectedNodes.containsKey(node)) {
            treeNode.removeFromParent();

            selectedNodes.get(node).hide();
            selectedNodes.remove(node);
        } else {
            model.insertNodeInto(treeNode, root, 0);

            TextAttributes attributes = new TextAttributes();
            attributes.setEffectColor(JBColor.blue);
            attributes.setEffectType(EffectType.ROUNDED_BOX);
            selectedNodes.put(node, node.addRangeHighlighter(attributes));
        }
        cardLayout.show(cardPanel, selectedNodes.isEmpty() ? "explanation" : "tree");

        // Although the changes are quite simple, this has to be called either way
        // to ensure the correct paths remain open
        updateValues();
    }

    private DefaultMutableTreeNode root() {
        return (DefaultMutableTreeNode) model.getRoot();
    }

    /**
     * Whenever the values that are to be shown in the inspector change,
     * this method must be called to ensure that the structure of the tree
     * is updated and the correct paths remain open.
     */
    public void updateValues() {
        DefaultMutableTreeNode root = root();
        root.removeAllChildren();
        for (Call.Node node : selectedNodes.keySet()) {
            model.insertNodeInto(node.freshInspectorTreeNode(), root, 0);
        }
        model.nodeStructureChanged(root);

        reopenPaths();
    }

    private void reopenPaths() {
        // Don't trigger treeExpanded in the listener when calling tree.expandPath
        reopening = true;

        try {
            DefaultMutableTreeNode root = root();
            for (int i = 0; i < root.getChildCount(); i++) {
                InspectorTreeNode valueRoot = (InspectorTreeNode) root.getChildAt(i);

                // The cast to Set is just to show that the .contains below is fast
                Set<List<String>> pathsForNode = (Set<List<String>>) openPaths.get(valueRoot.node);

                for (List<String> path : pathsForNode) {

                    /* Only expand the path if all parent paths are also open.
                       For example, if we've expanded the tree to look like:
                       A
                       |- B
                          |- C

                       and then we collapse A, the path to C is still in openPaths. The check below
                       ensures that we don't expand that path (when stepping a loop)
                       because its parent is not in openPaths anymore. Otherwise A would open again.

                       Later if the user expands A again, B will automatically expand because
                       treeExpanded calls reopenPaths.
                     */
                    if (IntStream.range(0, path.size())
                            .allMatch(j -> pathsForNode.contains(path.subList(0, j)))) {
                        tree.expandPath(labelsPathToTreePath(valueRoot, path));
                    }
                }
            }
        } finally {
            reopening = false;
        }
    }

    /**
     * Given a value 'root' and a list of labels representing the path
     * to some inner node, return a corresponding TreePath suitable for tree.expandPath.
     */
    private TreePath labelsPathToTreePath(TreeNode root, List<String> path) {
        for (String label : path) {
            boolean found = false;
            for (int i = 0; i < root.getChildCount(); i++) {
                TreeNode child = root.getChildAt(i);
                if (((InspectorTreeNode) child).label.equals(label)) {
                    root = child;
                    found = true;
                    break;
                }
            }

            if (!found) {
                return null;
            }
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

