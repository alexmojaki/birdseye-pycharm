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
import org.apache.commons.codec.digest.DigestUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

    static final Gson GSON = new GsonBuilder().create();

    private Utils() {
    }

    public static String truncate(String s, int maxLength) {
        if (s.length() <= maxLength + 5) {
            return s;
        }
        return s.substring(0, maxLength - 3) + "...";
    }

    public static String collapseWhitespace(String s) {
        return s.replaceAll("\\s{2,}", " ");
    }

    @NotNull
    static String hashFunction(PsiElement function) {
        return DigestUtils.sha256Hex(getFunctionText(function));
    }

    static String getFunctionText(PsiElement function) {
        int start = getFunctionStart(function) - function.getTextRange().getStartOffset();
        String text = function.getText().substring(start);
        assert text.startsWith("def");
        return text.trim();
    }

    static int getFunctionStart(PsiElement function) {
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

    private static <T> Stream<T> dropWhile(Stream<T> s, Predicate<T> p) {
        final boolean[] inTail = {false};
        return s.filter(i -> {
            if (inTail[0]) {
                return true;
            }
            if (!p.test(i)) {
                inTail[0] = true;
                return true;
            }
            return false;
        });
    }

    @NotNull
    static DocumentEx psiDocument(PsiElement psiFunction) {
        VirtualFile virtualFile = psiFunction.getContainingFile().getVirtualFile();
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
}
