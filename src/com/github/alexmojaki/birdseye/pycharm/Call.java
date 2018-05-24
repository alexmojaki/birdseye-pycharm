package com.github.alexmojaki.birdseye.pycharm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.stream.Collectors;

import static com.github.alexmojaki.birdseye.pycharm.Utils.psiDocument;

public class Call {
//    private static final Key<Node> NODE_KEY = Key.create(Node.class.getCanonicalName());

    private static final Gson GSON = new GsonBuilder().create();
    private String functionText;
    private CallData callData;
    CallPanel panel;
    private FunctionData functionData;
    DocumentEx document;
    private MultiMap<Range, Node> nodes = new MultiMap<>();
    Map<Integer, LoopNavigator> navigators = new TreeMap<>();
    Project project;
    private List<HideableRangeHighlighter> tempHighlighters = new ArrayList<>();
    Content toolWindowContent;
    BirdseyeFunction birdseyeFunction;
    CallMeta meta;

    static Call get(CallMeta callMeta, PsiElement psiFunction, BirdseyeFunction birdseyeFunction) {
        Call call = new Call();
        DocumentEx document = psiDocument(psiFunction);

        call.functionText = Utils.getFunctionText(psiFunction);
        call.project = psiFunction.getProject();


        ApiClient.CallResponse callResponse = MyProjectComponent.getInstance(call.project).apiClient.getCall(callMeta.id);
        if (callResponse == null) {
            return null;
        }

        call.callData = callResponse.call.data;
        call.functionData = callResponse.function.data;
        call.meta = callMeta;
        call.panel = new CallPanel(call);
        call.document = document;
        MyProjectComponent.getInstance(call.project).calls.add(call);
        call.birdseyeFunction = birdseyeFunction;
        call.init(psiFunction);
        return call;
    }

    private Call() {
    }

    private void init(PsiElement psiFunction) {
        int functionStart = birdseyeFunction.fullRangeMarker.getStartOffset();

        for (FunctionData.NodeRangesGroup nodeRangesGroup : functionData.node_ranges) {
            for (FunctionData.NodeRange nodeRange : nodeRangesGroup.nodes) {
//                if (!hasValue(nodeRange.node)) {
//                    continue;
//                }
                Range range = new Range(nodeRange.start, nodeRange.end);
                Node node = new Node(nodeRange, nodeRangesGroup.depth);
                nodes.putValue(range, node);
                node.rangeMarker = birdseyeFunction.rangeMarkers.get(range);
            }
        }

        SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(project);

        for (FunctionData.NodeRange loopNode : functionData.loop_nodes) {
            final PsiElement[] loopElement = {null};
            PsiRecursiveElementWalkingVisitor visitor = new PsiRecursiveElementWalkingVisitor() {
                @Override
                protected void elementFinished(@NotNull PsiElement element) {
                    TextRange textRange = element.getTextRange();
                    int start = textRange.getStartOffset() - functionStart;
                    int end = textRange.getEndOffset() - functionStart;
                    if (start == loopNode.start && end == loopNode.end) {
                        loopElement[0] = element;
                    }
                }
            };
            visitor.visitElement(psiFunction);

            LoopNavigator navigator = new LoopNavigator();
            navigator.pointer = smartPointerManager.createSmartPsiElementPointer(loopElement[0]);
            navigator.loopIndex = loopNode.node;
            navigators.put(navigator.loopIndex, navigator);
        }

        update();
    }

    private static final int[] EMPTY_INTS = {};

    void processHighlighters(Consumer<HideableRangeHighlighter> action) {
        for (HideableRangeHighlighter highlighter : tempHighlighters) {
            action.consume(highlighter);
        }
        for (Node node : nodes.values()) {
            HideableRangeHighlighter highlighter = node.selectedHighlighter;
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

    public class ExpandedValue {

        JsonArray arr;

        ExpandedValue(JsonArray arr) {
            this.arr = arr;
        }

        InspectorTreeNode treeNode(String prefix) {
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
                ExpandedValue child = new ExpandedValue(subarr.get(1).getAsJsonArray());
                InspectorTreeNode childTreeNode = child.treeNode(childPrefix);
                childTreeNode.index = i - 3;
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
        NodeRangesGroup[] node_ranges;
        NodeRange[] loop_nodes;
        Map<Integer, int[]> node_loops;

        static class NodeRangesGroup {
            int depth;
            NodeRange[] nodes;
        }

        static class NodeRange {
            int node;
            int start;
            int end;
            List<String> classes;
        }
    }

    private List<Node> nodesOverlappingWith(int offset) {
        List<Node> nodes = new ArrayList<>();
        document.processRangeMarkersOverlappingWith(offset, offset, rangeMarker -> {
            Range range = rangeMarker.getUserData(EyeLineMarkerProvider.ORIGINAL_RANGE);
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
                .max(Comparator.comparing(n -> n.depth))
                .orElse(null);
    }

    public class Node {

        FunctionData.NodeRange range;
        int depth;
        RangeMarker rangeMarker;
        HideableRangeHighlighter selectedHighlighter;

        InspectorTreeNode inspectorTreeNode = null;

        Node(FunctionData.NodeRange range, int depth) {
            this.range = range;
            this.depth = depth;
//            int f = functionRangeMarker.getStartOffset();
//            rangeMarker = document.createRangeMarker(range.start + f, range.end + f, true);
//            rangeMarker.putUserData(NODE_KEY, this);
        }

        int treeIndex() {
            return range.node;
        }

        String text() {
            return functionText.substring(range.start, range.end);
        }

        boolean isRangeInvalid() {
            TextRange textRange = new TextRange(
                    rangeMarker.getStartOffset(),
                    rangeMarker.getEndOffset());
            String originalText = text();
            String currentText = document.getText(textRange);
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
            return new HideableRangeHighlighter(
                    this,
                    attributes);
        }

        ExpandedValue value() {
            JsonElement element = callData.node_values.get(treeIndex());
            for (int loopIndex : functionData.node_loops.getOrDefault(treeIndex(), EMPTY_INTS)) {
                if (element == null) {
                    return null;
                }
                element = element.getAsJsonObject().get(String.valueOf(navigator(loopIndex).currentIteration()));
            }
            if (element == null) {
                return null;
            }
            return new ExpandedValue(element.getAsJsonArray());
        }

        InspectorTreeNode inspectorTreeNode() {
            if (inspectorTreeNode == null) {
                return freshInspectorTreeNode();
            }
            return inspectorTreeNode;
        }

        InspectorTreeNode freshInspectorTreeNode() {
            String prefix = Utils.truncate(Utils.collapseWhitespace(text()), 50);
            inspectorTreeNode = value().treeNode(prefix);
            inspectorTreeNode.index = treeIndex();
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
        int loopIndex;
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
            ExpandedValue value = n.value();
            return value != null && value.isException();
        }, attributes);
    }

    private void addTempHighlighters(Predicate<Node> predicate, TextAttributes attributes) {
        List<Node> filteredNodes = nodes.values().stream()
                .filter(predicate)
                .collect(Collectors.toList());

        for (Node node : filteredNodes) {
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
            navigator.indices = Arrays.stream(iterations)
                    .map(i -> i.index)
                    .collect(Collectors.toList());
            updateLoopIndices(iterations[navigator.currentIteration()].loops);
        }
    }


}