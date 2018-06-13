package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.openapi.editor.EditorLinePainter;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.LineExtensionInfo;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class HoverValueEditorLinePainter extends EditorLinePainter {

    static VirtualFile currentFile;
    static int currentLineNumber;
    static String repr;
    public static String currentProjectHash;

    @Override
    public Collection<LineExtensionInfo> getLineExtensions(@NotNull Project project, @NotNull VirtualFile file, int lineNumber) {
        if (!"".equals(repr)
                && project.getLocationHash().equals(currentProjectHash)
                && file.equals(currentFile)
                && lineNumber == currentLineNumber) {
            TextAttributes attributes = EditorColorsManager.getInstance().getGlobalScheme().getAttributes(HighlighterColors.TEXT).clone();
            attributes.setFontType(Font.ITALIC);
//            TextAttributes attributes = new TextAttributes(
//                    Gray._250,
//                    null,
//                    null,
//                    EffectType.ROUNDED_BOX,
//                    Font.ITALIC);
            return Collections.singletonList(new LineExtensionInfo("    " + repr, attributes));
        }
        return null;
    }

    private static boolean reordered = false;

    static void moveExtensionToBeginning() {
        if (reordered) {
            return;
        }
        ExtensionsArea area = Extensions.getArea(null);
        ExtensionPoint<EditorLinePainter> extensionPoint = area.getExtensionPoint(EditorLinePainter.EP_NAME);
        try {
            Field field = ExtensionPointImpl.class.getDeclaredField("myExtensionsCache");
            field.setAccessible(true);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (extensionPoint) {
                EditorLinePainter[] extensions = (EditorLinePainter[]) field.get(extensionPoint);
                if (extensions == null) {
                    return;
                }
                ArrayList<EditorLinePainter> list = new ArrayList<>(Arrays.asList(extensions));
                int foundIndex = -1;
                for (int i = 0; i < list.size(); i++) {
                    EditorLinePainter editorLinePainter = list.get(i);
                    if (editorLinePainter instanceof HoverValueEditorLinePainter) {
                        assert foundIndex == -1;
                        foundIndex = i;
                    }
                }
                if (foundIndex == -1) {
                    return;
                }
                list.add(0, list.remove(foundIndex));
                list.toArray(extensions);
                reordered = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
