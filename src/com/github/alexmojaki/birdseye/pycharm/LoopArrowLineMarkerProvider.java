package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.util.ConstantFunction;
import com.intellij.util.TripleFunction;
import org.apache.commons.lang.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

/**
 * Draw the left and right arrows for navigating loops
 */
public class LoopArrowLineMarkerProvider implements LineMarkerProvider {

    @Nullable
    @Override
    public LineMarkerInfo getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<PsiElement> elements, @NotNull Collection<LineMarkerInfo> result) {
        Map<PsiElement, Call.LoopNavigator> navigatorMap = new HashMap<>();

        for (Project project : new HashSet<>(mapToList(elements, PsiElement::getProject))) {
            MyProjectComponent component = MyProjectComponent.getInstance(project);
            for (Call call : component.activeCalls()) {
                for (Call.LoopNavigator navigator : call.navigators.values()) {
                    PsiElement element = navigator.pointer.getElement();
                    if (element == null) {
                        continue;
                    }
                    navigatorMap.put(element, navigator);
                }
            }
        }

        Set<Call.LoopNavigator> navigators = new HashSet<>();

        for (PsiElement element : elements) {
            Call.LoopNavigator navigator = navigatorMap.get(element);
            if (navigator == null || !navigators.add(navigator)) {
                continue;
            }

            TripleFunction<Icon, Integer, String, Void> add = (icon, direction, directionLabel) -> {
                String tooltip = String.format(
                        "Step loop of '%s' %s",
                        StringEscapeUtils.escapeHtml(element.getText()),
                        directionLabel);
                result.add(new LineMarkerInfo<>(
                        element,
                        element.getTextRange(),
                        navigator.canNavigate(direction) ? icon : IconLoader.getDisabledIcon(icon),
                        Pass.LINE_MARKERS,
                        new ConstantFunction<>(tooltip),
                        (e, elt) -> navigator.navigate(direction),
                        GutterIconRenderer.Alignment.LEFT
                ));
                return null;
            };

            add.fun(AllIcons.Actions.Back, -1, "backwards");
            add.fun(AllIcons.Actions.Forward, 1, "forwards");

        }

    }

}
