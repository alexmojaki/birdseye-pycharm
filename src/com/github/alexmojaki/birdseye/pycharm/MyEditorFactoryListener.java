package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.TextAnnotationGutterProvider;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MyEditorFactoryListener implements EditorFactoryListener {

//    static MultiMap<Document, Editor> documentToEditors = new MultiMap<>();

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();

        if (project == null) {
            return;
        }

        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);

//        documentToEditors.putValue(document, editor);

        editor.getGutter().registerTextAnnotation(new TextAnnotationGutterProvider() {
            @Override
            public String getLineText(int line, Editor editor) {

                List<Call.LoopNavigator> navigators = new ArrayList<>();
                for (Call call : MyProjectComponent.getInstance(project).activeCalls()) {
                    for (Call.LoopNavigator navigator : call.navigators.values()) {
                        PsiElement element = navigator.pointer.getElement();
                        if (element == null || !element.getContainingFile().getVirtualFile().equals(file)) {
                            continue;
                        }
                        int elementLine = editor.offsetToLogicalPosition(element.getTextOffset()).line;
                        if (elementLine == line) {
                            if (navigator.currentIterationDisplay() != null) {
                                navigators.add(navigator);
                            }
                        }
                    }

                    if (navigators.isEmpty()) {
                        continue;
                    }

                    //noinspection ConstantConditions
                    navigators.sort(Comparator.comparing(n -> n.pointer.getRange().getStartOffset()));

                    return navigators.stream()
                            .map(Call.LoopNavigator::currentIterationDisplay)
                            .collect(Collectors.joining(" "));
                }

                return null;
            }

            @Nullable
            @Override
            public String getToolTip(int line, Editor editor) {
                return null;
            }

            @Override
            public EditorFontType getStyle(int line, Editor editor) {
                return EditorFontType.PLAIN;
            }

            @Nullable
            @Override
            public ColorKey getColor(int line, Editor editor) {
                return null;
            }

            @Nullable
            @Override
            public Color getBgColor(int line, Editor editor) {
                return null;
            }

            @Override
            public List<AnAction> getPopupActions(int line, Editor editor) {
                return Collections.emptyList();
            }

            @Override
            public void gutterClosed() {
            }
        });

        for (Call call : MyProjectComponent.getInstance(project).activeCalls()) {
            call.processHighlighters(h -> h.addFor(editor));
        }
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
//        Editor editor = event.getEditor();
//        Document document = editor.getDocument();
//        documentToEditors.remove(document, editor);
    }
}