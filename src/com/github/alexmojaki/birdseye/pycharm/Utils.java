package com.github.alexmojaki.birdseye.pycharm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.codeInsight.daemon.impl.EditorTracker;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.diff.DiffColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.tree.TokenSet;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.psi.PyStatement;
import org.apache.commons.codec.digest.DigestUtils;
import org.jetbrains.annotations.Contract;
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
        return s.substring(0, maxLength / 2 - 1) +
                "..." +
                s.substring(s.length() - maxLength / 2 + 2);
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

    private static final TokenSet INSIGNIFICANT_TOKENS = TokenSet.orSet(
            PyTokenTypes.WHITESPACE_OR_LINEBREAK,
            TokenSet.create(PyTokenTypes.END_OF_LINE_COMMENT));

    /**
     * Returns the body of a function (PSI element), from the def token
     * until the last significant character of the last statement.
     */
    static String getFunctionText(PyFunction function) {
        int absoluteFunctionStart = function.getTextRange().getStartOffset();
        int start = getFunctionStart(function)
                - absoluteFunctionStart;

        // Find the last single-part statement, which may be nested inside
        // multipart statements (e.g. loops)
        final PyStatement[] lastStatement = {function};
        PsiRecursiveElementWalkingVisitor visitor = new PsiRecursiveElementWalkingVisitor() {
            @Override
            protected void elementFinished(@NotNull PsiElement element) {
                if (element instanceof PyStatement &&
                        element.getTextRange().getStartOffset() >
                                lastStatement[0].getTextRange().getStartOffset()) {
                    lastStatement[0] = (PyStatement) element;
                }
            }
        };
        visitor.visitElement(function);

        // Get the last node in that statement which isn't a comment or whitespace
        ASTNode lastNode = last(filterToList(
                lastStatement[0].getNode().getChildren(TokenSet.ANY),
                node -> !INSIGNIFICANT_TOKENS.contains(node.getElementType())));

        int end = lastNode.getTextRange().getEndOffset()
                - absoluteFunctionStart;
        String text = function.getText().substring(start, end);
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

    @SuppressWarnings("WeakerAccess")
    static <T> List<T> filterToList(T[] array, Predicate<T> predicate) {
        return Arrays.stream(array).filter(predicate).collect(Collectors.toList());
    }

    /**
     * Assert that the argument is null without having to make an extra statement and possibly a variable.
     * Easy way to silence some warnings.
     */
    @Contract(pure = true)
    @NotNull
    static <T> T notNull(T x) {
        assert x != null;
        return x;
    }

    @Contract(pure = true)
    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    static HideableRangeHighlighter addHoverHighlighter(Call.Node node) {
        TextAttributes attributes = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(DiffColors.DIFF_MODIFIED);
        return node.addRangeHighlighter(attributes);
    }
}
