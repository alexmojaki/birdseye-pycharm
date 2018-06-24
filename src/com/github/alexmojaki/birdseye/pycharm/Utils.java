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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Static utility methods
 */
public class Utils {

    static final Gson GSON = new GsonBuilder().create();

    private Utils() {
    }

    /**
     * Returns a string roughly at most maxLength characters,
     * replacing excess characters with "..." if needed.
     */
    @SuppressWarnings("SameParameterValue")
    static String truncate(String s, int maxLength) {
        if (s.length() <= maxLength + 5) {
            return s;
        }
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Return a string without multiple consecutive spaces
     */
    static String collapseWhitespace(String s) {
        return s.replaceAll("\\s{2,}", " ");
    }

    /**
     * Returns a hash of the body of a function (PSI element)
     */
    @NotNull
    static String hashFunction(PyFunction function) {
        return DigestUtils.sha256Hex(getFunctionText(function));
    }

    /**
     * Returns the body of a function (PSI element), from the def token
     * until the last non-space character.
     */
    static String getFunctionText(PyFunction function) {
        int start = getFunctionStart(function) - function.getTextRange().getStartOffset();
        String text = function.getText().substring(start);
        assert text.startsWith("def");
        return text.trim();
    }

    /**
     * Returns the position of a function (PSI element) where the def starts
     * (i.e. after the decorators)
     */
    static int getFunctionStart(PyFunction function) {
        return function
                .getNode()
                .getChildren(TokenSet.create(PyTokenTypes.DEF_KEYWORD))
                [0]
                .getStartOffset();
    }

    /**
     * Returns the list of active editors for this project, copied to allow changes.
     * Must be called in the ED thread.
     */
    static List<Editor> activeEditors(Project project) {
        EditorTracker editorTracker = project.getComponent(EditorTracker.class);
        List<Editor> activeEditors = editorTracker.getActiveEditors();
        return new ArrayList<>(activeEditors);
    }

    /**
     * Returns the document containing this element
     */
    @NotNull
    static DocumentEx psiElementDocument(PsiElement element) {
        VirtualFile virtualFile = element.getContainingFile().getVirtualFile();
        DocumentEx document = (DocumentEx) FileDocumentManager.getInstance().getDocument(virtualFile);
        assert document != null;
        return document;
    }

    /**
     * Returns contents wrapped in a tag with the given name.
     */
    static String tag(String tagName, String contents) {
        return String.format(
                "<%s>%s</%s>",
                tagName,
                contents,
                tagName);
    }

    /**
     * Returns HTML representing a list (ol or ul, determined by listTag)
     * with an entry for each item in contents.
     */
    static String htmlList(String listTag, List<String> contents) {
        String lis = contents
                .stream()
                .map(x -> tag("li", x))
                .collect(Collectors.joining("\n"));
        return tag(listTag, lis);
    }

    // Convenience functions for creating lists from streamable things

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

    /**
     * Assert that the argument is null without having to make an extra statement and possibly a variable.
     * Easy way to silence some warnings.
     */
    @NotNull static <T> T notNull(T x) {
        assert x != null;
        return x;
    }

}
