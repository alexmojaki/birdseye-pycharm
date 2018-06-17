package com.github.alexmojaki.birdseye.pycharm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.codeInsight.daemon.impl.EditorTracker;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.PyFunction;
import org.apache.commons.codec.digest.DigestUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Utils {

    static final Gson GSON = new GsonBuilder().create();

    private Utils() {
    }

    @SuppressWarnings("SameParameterValue")
    static String truncate(String s, int maxLength) {
        if (s.length() <= maxLength + 5) {
            return s;
        }
        return s.substring(0, maxLength - 3) + "...";
    }

    static String collapseWhitespace(String s) {
        return s.replaceAll("\\s{2,}", " ");
    }

    @NotNull
    static String hashFunction(PyFunction function) {
        return DigestUtils.sha256Hex(getFunctionText(function));
    }

    static String getFunctionText(PyFunction function) {
        int start = getFunctionStart(function) - function.getTextRange().getStartOffset();
        String text = function.getText().substring(start);
        assert text.startsWith("def");
        return text.trim();
    }

    static int getFunctionStart(PyFunction function) {
        return function
                .getNode()
                .getChildren(TokenSet.create(PyTokenTypes.DEF_KEYWORD))
                [0]
                .getStartOffset();
    }

    static List<Editor> activeEditors(Project project) {
        EditorTracker editorTracker = project.getComponent(EditorTracker.class);
        List<Editor> activeEditors = editorTracker.getActiveEditors();
        return new ArrayList<>(activeEditors);
    }

    @NotNull
    static DocumentEx psiElementDocument(PsiElement element) {
        VirtualFile virtualFile = element.getContainingFile().getVirtualFile();
        DocumentEx document = (DocumentEx) FileDocumentManager.getInstance().getDocument(virtualFile);
        assert document != null;
        return document;
    }

    static String tag(String tagName, String contents) {
        return String.format(
                "<%s>%s</%s>",
                tagName,
                contents,
                tagName);
    }

    static String htmlList(String listTag, List<String> contents) {
        String lis = contents
                .stream()
                .map(x -> tag("li", x))
                .collect(Collectors.joining("\n"));
        return tag(listTag, lis);
    }

    static <T, R> List<R> mapToList(Collection<? extends T> collection, Function<? super T, ? extends R> function) {
        return collection.stream().map(function).collect(Collectors.toList());
    }

    static <T, R> List<R> mapToList(T[] array, Function<? super T, ? extends R> function) {
        return Arrays.stream(array).map(function).collect(Collectors.toList());
    }

    static <T> List<T> filterToList(Collection<? extends T> collection, Predicate<T> predicate) {
        return collection.stream().filter(predicate).collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    static <T> List<T> filterToList(T[] array, Predicate<T> predicate) {
        return Arrays.stream(array).filter(predicate).collect(Collectors.toList());
    }

}
