package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.ConstantFunction;
import com.intellij.util.TripleFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class LoopArrowLineMarkerProvider implements LineMarkerProvider {

    @Nullable
    @Override
    public LineMarkerInfo getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<PsiElement> elements, @NotNull Collection<LineMarkerInfo> result) {
        if (elements.isEmpty()) {
            return;
        }

        Map<LoopNavigatorKey, Call.LoopNavigator> navigatorMap = new HashMap<>();
        Project project = elements.get(0).getProject();
        MyProjectComponent component = MyProjectComponent.getInstance(project);

        for (Call call : component.activeCalls()) {
            for (Call.LoopNavigator navigator : call.navigators.values()) {
                PsiElement element = navigator.pointer.getElement();
                if (element == null) {
                    continue;
                }
                navigatorMap.put(LoopNavigatorKey.from(element), navigator);
            }
        }

        Set<Call.LoopNavigator> navigators = new HashSet<>();

        for (PsiElement element : elements) {
            LoopNavigatorKey navigatorKey = LoopNavigatorKey.from(element);
            Call.LoopNavigator navigator = navigatorMap.get(navigatorKey);
            if (navigator == null || !navigators.add(navigator)) {
                continue;
            }

            TripleFunction<Icon, Integer, String, Object> add = (icon, direction, directionLabel) -> {
                String tooltip = String.format("Step loop of '%s' %s", element.getText(), directionLabel);
                return result.add(new LineMarkerInfo<>(
                        element,
                        element.getTextRange(),
                        navigator.canNavigate(direction) ? icon : IconLoader.getDisabledIcon(icon),
                        Pass.LINE_MARKERS,
                        new ConstantFunction<>(tooltip),
                        (e, elt) -> navigator.navigate(direction),
                        GutterIconRenderer.Alignment.LEFT
                ));
            };

            add.fun(AllIcons.Actions.Back, -1, "backwards");
            add.fun(AllIcons.Actions.Forward, 1, "forwards");

        }

    }

    private static class LoopNavigatorKey {

        VirtualFile file;
        int startOffset;
        int length;

        LoopNavigatorKey(VirtualFile file, int startOffset, int length) {
            this.file = file;
            this.startOffset = startOffset;
            this.length = length;
        }

        static LoopNavigatorKey from(PsiElement element) {
            return new LoopNavigatorKey(
                    element.getContainingFile().getVirtualFile(),
                    element.getTextOffset(),
                    element.getTextLength());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LoopNavigatorKey that = (LoopNavigatorKey) o;
            return startOffset == that.startOffset &&
                    length == that.length &&
                    Objects.equals(file, that.file);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, startOffset, length);
        }

        @Override
        public String toString() {
            return "LoopNavigatorKey{" +
                    "startOffset=" + startOffset +
                    ", length=" + length +
                    ", file=" + file +
                    '}';
        }
    }
}
