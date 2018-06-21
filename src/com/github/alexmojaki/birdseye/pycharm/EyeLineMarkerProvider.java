package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.ConstantFunction;
import com.intellij.util.Consumer;
import com.jetbrains.python.debugger.array.JBTableWithRowHeaders;
import com.jetbrains.python.psi.PyFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

/**
 * This puts the birdseye icon in the gutter next to functions which have been traced.
 */
public class EyeLineMarkerProvider implements LineMarkerProvider {

    @Nullable
    @Override
    public LineMarkerInfo getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    /**
     * Find Python functions with body hashes matching those retrieved from the server,
     * and add a line marker to them.
     */
    @Override
    public void collectSlowLineMarkers(@NotNull List<PsiElement> elements, @NotNull Collection<LineMarkerInfo> result) {
        for (PsiElement element : elements) {
            if (!(element instanceof PyFunction)) {
                continue;
            }
            PyFunction function = (PyFunction) element;
            PsiElement nameIdentifier = function.getNameIdentifier();
            if (nameIdentifier == null) {
                continue;
            }

            String hash = hashFunction(function);
            MyProjectComponent component = MyProjectComponent.getInstance(element.getProject());
            Boolean hasCalls = component.functionHashes.get(hash);
            if (hasCalls == null) {
                continue;
            }

            result.add(new LineMarkerInfo<>(
                    nameIdentifier,
                    nameIdentifier.getTextRange(),
                    hasCalls ?
                            MyProjectComponent.BIRDSEYE_ICON :
                            MyProjectComponent.BIRDSEYE_EMPTY_ICON,
                    Pass.LINE_MARKERS,
                    new ConstantFunction<>(
                            hasCalls ?
                                    "View calls in birdseye" :
                                    "There are no calls for this function in birdseye"),
                    (_e, elt) -> createCallsListPanel((PyFunction) elt.getParent()),
                    GutterIconRenderer.Alignment.RIGHT
            ));
        }
    }

    /**
     * Called when one of the gutter icons is clicked. Shows a panel in the birdseye tool window
     * with a list of calls for the function.
     */
    private void createCallsListPanel(PyFunction psiFunction) {
        final Project project = psiFunction.getProject();
        MyProjectComponent component = MyProjectComponent.getInstance(project);

        String hash = hashFunction(psiFunction);

        ApiClient.CallsByHashResponse response = component.apiClient.listCallsByBodyHash(hash);
        if (response == null) {
            return;
        }
        List<CallMeta> rows = response.calls;
        JComponent centralComponent;
        final Consumer<Integer> openRow;

        if (rows.isEmpty()) {
            centralComponent = new JBLabel(
                    "<html><h2>No calls found for this function. </h2>" +
                            "<p>This means that the function definition ran, <br>" +
                            "but never the function itself.</p></html>"
            );
            openRow = null;
        } else {
            BirdseyeFunction function = new BirdseyeFunction(psiFunction, response);

            final JTable table = new JBTableWithRowHeaders(true);
            centralComponent = table;

            final String[] columns = new String[]{"Start time", "Arguments", "Result"};

            table.setModel(new AbstractTableModel() {

                @Override
                public String getColumnName(int column) {
                    return columns[column];
                }

                @Override
                public int getRowCount() {
                    return rows.size();
                }

                @Override
                public int getColumnCount() {
                    return columns.length;
                }

                @Override
                public Object getValueAt(int rowIndex, int columnIndex) {
                    CallMeta meta = rows.get(rowIndex);
                    switch (columnIndex) {
                        case 0:
                            return meta.startTime();
                        case 1:
                            return tag("html", meta.argumentsList());
                        case 2:
                            return meta.exception != null ? meta.exception : meta.return_value;
                        default:
                            throw new RuntimeException(columnIndex + "");
                    }
                }
            });
            table.setFillsViewportHeight(true);

            openRow = (row) -> {
                if (row < 0) {
                    return;
                }
                CallMeta callMeta = rows.get(row);
                Content content = null;

                for (Call call : component.calls) {
                    if (call.meta.id.equals(callMeta.id)) {
                        content = call.toolWindowContent;
                        break;
                    }
                }
                ContentManager contentManager = component.contentManager();

                if (content == null) {
                    Call call = Call.get(callMeta, psiFunction, function);
                    if (call == null) {
                        return;
                    }
                    JPanel panel = new PanelWithSideButtons(project, call.panel);
                    content = ContentFactory.SERVICE.getInstance()
                            .createContent(
                                    panel,
                                    "Call to " + psiFunction.getName(),
                                    false);
                    call.toolWindowContent = content;
                    contentManager.addContent(content);
                    content.setIcon(AllIcons.General.Run);
                }

                contentManager.setSelectedContent(content);
            };

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openRow.consume(table.rowAtPoint(e.getPoint()));
                }
            });

        }

        JPanel panel = new PanelWithSideButtons(project, centralComponent);

        Content content = ContentFactory.SERVICE.getInstance().createContent(
                panel,
                "Calls list for " + psiFunction.getName(),
                false);

        content.setIcon(AllIcons.Nodes.DataTables);
        component.setCallsListContent(content);

        if (rows.size() == 1) {
            assert openRow != null;
            openRow.consume(0);
        }
    }

}
