package com.github.alexmojaki.birdseye.pycharm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.util.containers.MultiMap;
import com.jetbrains.python.psi.PyFunction;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

public class Call {

    private CallData callData;
    CallPanel panel;
    private FunctionData functionData;
    private MultiMap<Range, Node> nodes = new MultiMap<>();

    // A key here is a _tree_index from birdseye
    Map<Integer, LoopNavigator> navigators = new TreeMap<>();

    Project project;

    // These are highlighters that disappear (possibly to be replaced by identical versions)
    // when the user navigates through a loop.
    // They include red boxes for exceptions and gray text for uncovered code.
    // They are temporary only in contrast to the highlighter for a selected node
    // which survives loop navigations.
    private List<HideableRangeHighlighter> tempHighlighters = new ArrayList<>();

    Content toolWindowContent;
    BirdseyeFunction birdseyeFunction;
    CallMeta meta;

    /**
     * Gets all the data about a call from the server. If there's an error, returns null.
     * Otherwise, returns an initialised Call.
     */
    static Call get(CallMeta callMeta, PyFunction psiFunction, BirdseyeFunction birdseyeFunction) {
        Call call = new Call();

        call.project = psiFunction.getProject();

        MyProjectComponent component = MyProjectComponent.getInstance(call.project);
        ApiClient.CallResponse callResponse = component.apiClient.getCall(callMeta.id);
        if (callResponse == null) {  // indicates an error reaching the server
            return null;
        }

        call.callData = callResponse.call.data;
        call.functionData = callResponse.function.data;
        call.meta = callMeta;
        call.panel = new CallPanel(call);
        call.birdseyeFunction = birdseyeFunction;
        call.init(psiFunction);
        component.calls.add(call);
        return call;
    }

    private Call() {
    }

    private void init(PyFunction psiFunction) {
        for (NodeRange nodeRange : functionData.node_ranges) {
            Node node = new Node(nodeRange);
            nodes.putValue(nodeRange.plainRange(), node);
        }

        // For the Nodes, it's enough to identify them by just a Range (above)
        // For loop nodes, we need to be able to create line markers
        // (see LoopArrowLineMarkerProvider) which requires a PsiElement.
        // We use either the target of a for loop, e.g. `for *i* in ...:`,
        // or the condition in a while loop, e.g. `while *condition*:`.

        SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(project);

        for (LoopNodeRange loopNode : functionData.loop_nodes) {
            // Find a PsiElement with the correct text range
            RangeMarker rangeMarker = birdseyeFunction.loopRangeMarkers.get(loopNode.plainRange());

            if (!rangeMarker.isValid()) {
                continue;
            }

            final PsiElement[] loopElement = {null};
            PsiRecursiveElementWalkingVisitor visitor = new PsiRecursiveElementWalkingVisitor() {
                @Override
                protected void elementFinished(@NotNull PsiElement element) {
                    TextRange textRange = element.getTextRange();
                    if (textRange.getStartOffset() == rangeMarker.getStartOffset() &&
                            textRange.getEndOffset() == rangeMarker.getEndOffset()) {
                        loopElement[0] = element;
                    }
                }
            };
            visitor.visitElement(psiFunction);

            if (loopElement[0] == null) {
                continue;
            }

            LoopNavigator navigator = new LoopNavigator();
            navigator.pointer = smartPointerManager.createSmartPsiElementPointer(loopElement[0]);
            navigator.treeIndex = loopNode.tree_index;
            navigators.put(navigator.treeIndex, navigator);
        }

        update();
    }

    DocumentEx document() {
        return birdseyeFunction.document;
    }

    private static final int[] EMPTY_INTS = {};

    /**
     * Perform action on every highlighter this call manages.
     */
    void processHighlighters(Consumer<HideableRangeHighlighter> action) {
        tempHighlighters.forEach(action);
        panel.selectedNodes.values().forEach(action);
    }

    public void hideHighlighters() {
        processHighlighters(HideableRangeHighlighter::hide);
    }

    public void showHighlighters() {
        processHighlighters(HideableRangeHighlighter::show);
    }

    /**
     * This corresponds to the NodeValue Python class in birdseye.
     * It has data stored as JSON about a Node for a particular iteration in
     * a call. The data is interpreted, particularly to be displayed
     * in a tree in the inspector panel.
     *
     * This corresponds somewhat to the make_jstree_nodes function in call.js in plain birdseye,
     * but better organised.
     */
    public class NodeValue {

        JsonArray arr;

        NodeValue(JsonArray arr) {
            this.arr = arr;
        }

        @NotNull InspectorTreeNode treeNode(String prefix) {
            InspectorTreeNode result;
            if (isExpression()) {
                String typeName = typeName();
                Icon icon = AllIcons.Debugger.Value;
                if (typeIndex() < callData.num_special_types) {
                    switch (typeName) {
                        case "list":
                        case "tuple":
                            icon = AllIcons.Json.Array;
                            break;
                        case "set":
                        case "dict":
                        case "frozenset":
                            icon = AllIcons.Json.Object;
                            break;
                        case "int":
                        case "float":
                        case "long":
                        case "complex":
                            icon = AllIcons.Debugger.Db_primitive;
                            break;
                    }
                }
                result = new InspectorTreeNode.Expression(icon, typeName, repr());
            } else if (isStatement()) {
                result = new InspectorTreeNode.Statement();
            } else {
                assert isException();
                result = new InspectorTreeNode.Exception(repr());
            }

            result.label = prefix;

            for (int i = 3; i < arr.size(); i++) {
                JsonArray subarr = arr.get(i).getAsJsonArray();
                String childPrefix = subarr.get(0).getAsString();
                NodeValue child = new NodeValue(subarr.get(1).getAsJsonArray());
                InspectorTreeNode childTreeNode = child.treeNode(childPrefix);
                result.add(childTreeNode);
            }

            Map meta = meta();
            if (meta.containsKey("len")) {
                int len = ((Double) meta.get("len")).intValue();
                result.add(new InspectorTreeNode.Len(len));
            }

            return result;
        }

        String typeName() {
            int i = typeIndex();
            if (i < 0) {
                return null;
            }
            return callData.type_names[i];
        }

        int typeIndex() {
            return arr.get(1).getAsInt();
        }

        String repr() {
            return arr.get(0).getAsString();
        }

        Map meta() {
            return GSON.fromJson(arr.get(2), Map.class);
        }

        boolean isStatement() {
            return typeIndex() == -2;
        }

        boolean isException() {
            return typeIndex() == -1;
        }

        boolean isExpression() {
            return typeIndex() >= 0;
        }

        boolean isNotInteresting() {
            return isStatement() && meta().isEmpty();
        }

    }

    // These classes correspond to JSON returned by the API,
    // pretty much as they are in the database

    /**
     * The data column of the call table
     */
    public static class CallData {
        Map<Integer, JsonElement> node_values;
        String[] type_names;
        Loops loop_iterations;
        int num_special_types;
    }

    /**
     * The Iteration class in Python birdseye
     */
    static class Iteration {
        int index;
        Loops loops;
    }

    // Basically a type alias
    static class Loops extends HashMap<Integer, Iteration[]> {
    }

    /**
     * The data column of the function table
     */
    static class FunctionData {
        NodeRange[] node_ranges;
        LoopNodeRange[] loop_nodes;
        Map<Integer, int[]> node_loops;
    }

    private abstract static class AbstractNodeRange {
        int tree_index;
        int start;
        int end;

        Range plainRange() {
            return new Range(start, end);
        }
    }

    static class NodeRange extends AbstractNodeRange {
        int depth;
        List<String> classes;
    }

    static class LoopNodeRange extends AbstractNodeRange {
    }

    /**
     * Returns a list of nodes corresponding to range markers that
     * contain (inclusively) the given offset in the document.
     */
    private List<Node> nodesOverlappingWith(int offset) {
        List<Node> nodes = new ArrayList<>();
        document().processRangeMarkersOverlappingWith(offset, offset, rangeMarker -> {
            Range range = rangeMarker.getUserData(BirdseyeFunction.ORIGINAL_RANGE);
            if (range != null) {
                for (Node node : this.nodes.get(range)) {
                    if (node != null) {
                        nodes.add(node);
                    }
                }
            }
            return true;
        });
        return nodes;
    }

    /**
     * Return the deepest (i.e. most specific) node that has a value
     * and whose range contains both offset and offset+1. The idea is
     * that the mouse cursor is between offset and offset+1.
     */
    public Node nodeAtPosition(int offset) {
        List<Node> nodes = nodesOverlappingWith(offset);
        nodes.retainAll(nodesOverlappingWith(offset + 1));
        return nodes.stream()
                .filter(n -> n.value() != null)
                .max(Comparator.comparing(n -> n.range.depth))
                .orElse(null);
    }

    /**
     * This corresponds to a node in the Python AST that may have a value to inspect.
     */
    public class Node {

        final NodeRange range;
        InspectorTreeNode inspectorTreeNode = null;

        Node(NodeRange range) {
            this.range = range;
        }

        int treeIndex() {
            return range.tree_index;
        }

        String text() {
            return birdseyeFunction.originalText.substring(range.start, range.end);
        }

        RangeMarker rangeMarker() {
            return birdseyeFunction.rangeMarkers.get(range.plainRange());
        }

        /**
         * Returns true if the text in this node's range in the document has
         * been edited, so that the text in the editor may not reflect what this node
         * actually represents, thus potentially confusing the user.
         */
        boolean isRangeInvalid() {
            RangeMarker rangeMarker = rangeMarker();

            if (!rangeMarker.isValid()) {
                return false;
            }

            TextRange textRange = new TextRange(
                    rangeMarker.getStartOffset(),
                    rangeMarker.getEndOffset());
            String originalText = text();
            String currentText = document().getText(textRange);
            boolean result = originalText.equals(currentText);
            if (
                // If the text doesn't match exactly, the range might still be valid
                // if the user has just added some insignificant whitespace, which is possible if...
                    !result
                            // the node represents an expression (so indentation doesn't matter)
                            && range.classes.isEmpty()
                            // and the expression doesn't contain strings. We don't actually check
                            // if the whitespace is inside a string because that's too hard, so
                            // this is a little more restrictive than necessary
                            && !originalText.contains("'")
                            && !originalText.contains("\"")
                    ) {
                // Check if whitespace is the only difference
                originalText = originalText.replaceAll("\\s", "");
                currentText = currentText.replaceAll("\\s", "");
                result = originalText.equals(currentText);
            }
            return !result;
        }

        HideableRangeHighlighter addRangeHighlighter(TextAttributes attributes) {
            return new HideableRangeHighlighter(this, attributes);
        }

        /**
         * Return the value of the node at the 'current time' in the program
         * based on the current loop iterations.
         *
         * Returns null if there is no value at this time because the code didn't execute,
         * e.g. because of an if statement.
         *
         * This corresponds to the `get_value` function in call.js in plain birdseye.
         */
        NodeValue value() {
            JsonElement element = callData.node_values.get(treeIndex());
            for (int loopIndex : functionData.node_loops.getOrDefault(treeIndex(), EMPTY_INTS)) {
                LoopNavigator navigator = navigators.get(loopIndex);
                if (element == null || navigator == null) {
                    return null;
                }
                element = element.getAsJsonObject().get(String.valueOf(navigator.currentIteration()));
            }
            if (element == null) {
                return null;
            }
            return new NodeValue(element.getAsJsonArray());
        }

        @NotNull InspectorTreeNode inspectorTreeNode() {
            if (inspectorTreeNode == null) {
                return freshInspectorTreeNode();
            }
            return inspectorTreeNode;
        }

        @NotNull InspectorTreeNode freshInspectorTreeNode() {
            String prefix = truncate(collapseWhitespace(text()), 50);
            inspectorTreeNode = value().treeNode(prefix);
            inspectorTreeNode.node = this;
            return inspectorTreeNode;
        }

        Call call() {
            return Call.this;
        }
    }

    /**
     * This class manages the state of a loop, both so that nodes can know
     * their current value and to display the arrows and iteration number on the side.
     *
     * Suppose there is a loop in the code that iterates 100 times. birdseye will keep the first
     * and last few iterations of this loop.
     *
     * - `indices` will be [0, 1, 2, 97, 98, 99]
     * - currentIterationDisplay() will return one of the above values.
     * - iterationIndex and currentIteration() will be an index of `indices`, i.e. one of the values
     *     [0, 1, 2, 3, 4, 5].
     *
     * Now suppose there is a nested loop, and the number of iterations of the inner loop varies.
     * The user steps forward through the inner loop, until iterationIndex is 5.
     * Then they step in the outer loop and now the inner loop only has 3 iterations.
     * iterationIndex will remain 5, so that if the user returns to where they were in the outer loop,
     * everything goes back to how it was.
     * But currentIteration() will return 2, the last possible index of `indices`.
     */
    class LoopNavigator {
        int iterationIndex = 0;
        List<Integer> indices;
        int treeIndex;  // the tree_index of the loop statement
        SmartPsiElementPointer pointer;

        private int currentIteration() {
            return Math.min(iterationIndex, indices.size() - 1);
        }

        String currentIterationDisplay() {
            int i = currentIteration();
            if (0 <= i && i < indices.size()) {
                return indices.get(i) + "";
            }
            return null;
        }

        // In the methods below, `direction` is either:
        // -1, meaning step backwards, the arrow pointing left, or
        // +1, meaning step forwards,  the arrow pointing right

        boolean canNavigate(int direction) {
            int result = currentIteration() + direction;
            return 0 <= result && result < indices.size();
        }

        void navigate(int direction) {
            if (canNavigate(direction)) {
                iterationIndex += direction;
                update();
            }
        }

    }

    /**
     * This is called when either the call is first created and viewed,
     * or when the user steps through a loop. The nodes get fresh values
     * and the appearance of many things can change.
     *
     * This corresponds somewhat to the render() function in call.js.
     */
    private void update() {
        // Updating the state of loops.
        for (LoopNavigator navigator : navigators.values()) {
            navigator.indices = Collections.emptyList();
        }
        updateLoopIndices(callData.loop_iterations);

        // This kicks off checking for line markers, particularly letting
        // LoopArrowLineMarkerProvider show new arrows.
        DaemonCodeAnalyzer.getInstance(project).restart();

        // Update the tree in the inspector
        panel.updateValues();

        // Update temporary highlighters, i.e. uncovered statements and exceptions

        for (HideableRangeHighlighter highlighter : tempHighlighters) {
            highlighter.hide();
        }
        tempHighlighters.clear();

        TextAttributes attributes = new TextAttributes();
        attributes.setForegroundColor(JBColor.GRAY);
        addTempHighlighters(n -> n.value() == null && n.range.classes.contains("stmt"), attributes);

        attributes = new TextAttributes();
        attributes.setEffectType(EffectType.ROUNDED_BOX);
        attributes.setEffectColor(JBColor.RED);
        addTempHighlighters(n -> {
            NodeValue value = n.value();
            return value != null && value.isException();
        }, attributes);
    }

    private void addTempHighlighters(Predicate<Node> predicate, TextAttributes attributes) {
        for (Node node : filterToList(nodes.values(), predicate)) {
            tempHighlighters.add(node.addRangeHighlighter(attributes));
        }
    }

    /**
     * Recursively update the loop navigators to have the correct list
     * of indices based on the current loop iterations.
     *
     * Corresponds to the findRanges() function in call.js
     */
    private void updateLoopIndices(Loops loops) {
        if (loops == null || loops.isEmpty()) {
            return;
        }
        for (Integer loopIndex : loops.keySet()) {
            Iteration[] iterations = loops.get(loopIndex);
            LoopNavigator navigator = navigators.get(loopIndex);
            if (navigator == null) {
                continue;
            }
            navigator.indices = mapToList(iterations, i -> i.index);
            updateLoopIndices(iterations[navigator.currentIteration()].loops);
        }
    }

    /**
     * Since Call holds a lot of data, when we no longer need one,
     * we get rid of references to the data just in case there's a
     * memory leak somewhere.
     */
    void clearMemoryJustInCase() {
        callData.loop_iterations.clear();
        callData.node_values.clear();
        callData = null;
        for (Node node : panel.selectedNodes.keySet()) {
            InspectorTreeNode treeNode = node.inspectorTreeNode;

            // We don't expect this to happen, but at this point
            // we're being cautious
            if (treeNode == null) {
                continue;
            }

            treeNode.removeFromParent();
        }
        for (Node node : nodes.values()) {
            node.inspectorTreeNode = null;
        }
        panel.selectedNodes.clear();
        panel = null;
        functionData.loop_nodes = null;
        functionData.node_loops.clear();
        functionData.node_ranges = null;
        functionData = null;
        nodes.clear();
        navigators.clear();
        project = null;
        tempHighlighters.clear();
        toolWindowContent = null;
        meta = null;
    }


}
