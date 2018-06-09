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
import com.intellij.util.Consumer;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.*;
import java.util.function.Predicate;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

public class Call {

    private CallData callData;
    CallPanel panel;
    private FunctionData functionData;
    private MultiMap<Range, Node> nodes = new MultiMap<>();
    Map<Integer, LoopNavigator> navigators = new TreeMap<>();
    Project project;
    private List<HideableRangeHighlighter> tempHighlighters = new ArrayList<>();
    Content toolWindowContent;
    BirdseyeFunction birdseyeFunction;
    CallMeta meta;

    static Call get(CallMeta callMeta, PsiElement psiFunction, BirdseyeFunction birdseyeFunction) {
        Call call = new Call();

        call.project = psiFunction.getProject();

        MyProjectComponent component = MyProjectComponent.getInstance(call.project);
        ApiClient.CallResponse callResponse = component.apiClient.getCall(callMeta.id);
        if (callResponse == null) {
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

    private void init(PsiElement psiFunction) {
        for (NodeRange nodeRange : functionData.node_ranges) {
            Node node = new Node(nodeRange);
            nodes.putValue(nodeRange.plainRange(), node);
        }

        SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(project);

        for (NodeRange loopNode : functionData.loop_nodes) {
            RangeMarker rangeMarker = birdseyeFunction.loopRangeMarkers.get(loopNode.plainRange());
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
            navigator.treeIndex = loopNode.node;
            navigators.put(navigator.treeIndex, navigator);
        }

        update();
    }

    DocumentEx document() {
        return birdseyeFunction.document;
    }

    private static final int[] EMPTY_INTS = {};

    void processHighlighters(Consumer<HideableRangeHighlighter> action) {
        for (HideableRangeHighlighter highlighter : tempHighlighters) {
            action.consume(highlighter);
        }
        for (Node node : nodes.values()) {
            HideableRangeHighlighter highlighter = panel.selectedNodes.get(node);
            if (highlighter != null) {
                action.consume(highlighter);
            }
        }

    }

    public void hideHighlighters() {
        processHighlighters(HideableRangeHighlighter::hide);
    }

    public void showHighlighters() {
        processHighlighters(HideableRangeHighlighter::show);
    }

    public class NodeValue {

        JsonArray arr;

        NodeValue(JsonArray arr) {
            this.arr = arr;
        }

        InspectorTreeNode treeNode(int index, String prefix) {
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
            result.index = index;

            for (int i = 3; i < arr.size(); i++) {
                JsonArray subarr = arr.get(i).getAsJsonArray();
                String childPrefix = subarr.get(0).getAsString();
                NodeValue child = new NodeValue(subarr.get(1).getAsJsonArray());
                InspectorTreeNode childTreeNode = child.treeNode(i - 3, childPrefix);
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

    public static class CallData {
        Map<Integer, JsonElement> node_values;
        String[] type_names;
        Loops loop_iterations;
        int num_special_types;
    }

    static class Iteration {
        int index;
        Loops loops;
    }

    static class Loops extends HashMap<Integer, Iteration[]> {
    }

    static class FunctionData {
        NodeRange[] node_ranges;
        NodeRange[] loop_nodes;
        Map<Integer, int[]> node_loops;
    }

    static class NodeRange {
        int depth;
        int node;
        int start;
        int end;
        List<String> classes;

        Range plainRange() {
            return new Range(start, end);
        }
    }

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

    public Node nodeAtPosition(int offset) {
        List<Node> nodes = nodesOverlappingWith(offset);
        nodes.retainAll(nodesOverlappingWith(offset + 1));
        return nodes.stream()
                .filter(n -> n.value() != null)
                .max(Comparator.comparing(n -> n.range.depth))
                .orElse(null);
    }

    public class Node {

        final NodeRange range;
        InspectorTreeNode inspectorTreeNode = null;

        Node(NodeRange range) {
            this.range = range;
        }

        int treeIndex() {
            return range.node;
        }

        String text() {
            return birdseyeFunction.originalText.substring(range.start, range.end);
        }

        RangeMarker rangeMarker() {
            return birdseyeFunction.rangeMarkers.get(range.plainRange());
        }

        boolean isRangeInvalid() {
            TextRange textRange = new TextRange(
                    rangeMarker().getStartOffset(),
                    rangeMarker().getEndOffset());
            String originalText = text();
            String currentText = document().getText(textRange);
            boolean result = originalText.equals(currentText);
            if (!result && range.classes.isEmpty() &&
                    !originalText.contains("'") &&
                    !originalText.contains("\"")) {
                originalText = originalText.replaceAll("\\s", "");
                currentText = currentText.replaceAll("\\s", "");
                result = originalText.equals(currentText);
            }
            return !result;
        }

        HideableRangeHighlighter addRangeHighlighter(TextAttributes attributes) {
            return new HideableRangeHighlighter(this, attributes);
        }

        NodeValue value() {
            JsonElement element = callData.node_values.get(treeIndex());
            for (int loopIndex : functionData.node_loops.getOrDefault(treeIndex(), EMPTY_INTS)) {
                LoopNavigator navigator = navigator(loopIndex);
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

        InspectorTreeNode inspectorTreeNode() {
            if (inspectorTreeNode == null) {
                return freshInspectorTreeNode();
            }
            return inspectorTreeNode;
        }

        InspectorTreeNode freshInspectorTreeNode() {
            String prefix = truncate(collapseWhitespace(text()), 50);
            inspectorTreeNode = value().treeNode(treeIndex(), prefix);
            return inspectorTreeNode;
        }

        Call call() {
            return Call.this;
        }
    }

    private LoopNavigator navigator(int loopIndex) {
        return navigators.get(loopIndex);
    }

    class LoopNavigator {
        int iterationIndex = 0;
        int treeIndex;
        SmartPsiElementPointer pointer;
        List<Integer> indices;

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

    private void update() {
        for (LoopNavigator navigator : navigators.values()) {
            navigator.indices = Collections.emptyList();
        }
        updateLoopIndices(callData.loop_iterations);
        DaemonCodeAnalyzer.getInstance(project).restart();
        panel.updateValues();

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

    private void updateLoopIndices(Loops loops) {
        if (loops == null || loops.isEmpty()) {
            return;
        }
        for (Integer loopIndex : loops.keySet()) {
            Iteration[] iterations = loops.get(loopIndex);
            LoopNavigator navigator = navigator(loopIndex);
            if (navigator == null) {
                continue;
            }
            navigator.indices = mapToList(iterations, i -> i.index);
            updateLoopIndices(iterations[navigator.currentIteration()].loops);
        }
    }


}
