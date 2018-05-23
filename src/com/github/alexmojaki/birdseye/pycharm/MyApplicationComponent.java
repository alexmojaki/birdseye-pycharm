package com.github.alexmojaki.birdseye.pycharm;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

public class MyApplicationComponent implements ApplicationComponent {

    MyApplicationComponent() {
        EditorEventMulticaster eventMulticaster = EditorFactory.getInstance().getEventMulticaster();
        eventMulticaster.addEditorMouseMotionListener(HoverListener.INSTANCE);
        eventMulticaster.addEditorMouseListener(HoverListener.INSTANCE);

        EditorFactory.getInstance().addEditorFactoryListener(new MyEditorFactoryListener(),
                () -> {
                });
    }

    static MyApplicationComponent getInstance() {
        return ApplicationManager.getApplication().getComponent(MyApplicationComponent.class);
    }

    void updateServers() {
        Table<String, String, MyProjectComponent> table = getServersBySettingsTable();
        for (String port : table.rowKeySet()) {
            Map<String, MyProjectComponent> forPort = table.row(port);
            for (MyProjectComponent component : forPort.values()) {
                if (forPort.size() > 1) {
                    List<String> items = forPort.values()
                            .stream()
                            .map(c -> String.format("%s (<code>%s</code>)",
                                    escapeHtml(c.getProject().getName()),
                                    escapeHtml(component.state.dbUrl)))
                            .collect(Collectors.toList());

                    String title = "Multiple projects running birdseye on the same port with different database URLs";
                    String message = Utils.htmlList("ul", items);
                    component.notifyError(title, message);
                    component.processMonitor.errorMessage = Utils.tag("html",
                            title + " <br> " + message);

                } else {
                    ProcessMonitor processMonitor = component.processMonitor;
                    if (!processMonitor.isRunning()) {
                        processMonitor.start();
                    }
                    assert !processMonitor.stateOutdated();
                }
            }
        }
    }

    Table<String, String, MyProjectComponent> getServersBySettingsTable() {
        Table<String, String, MyProjectComponent> table = HashBasedTable.create();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            MyProjectComponent component = MyProjectComponent.getInstance(project);
            State state = component.state;
            ProcessMonitor processMonitor = component.processMonitor;
            if (state.runServer) {
                if (processMonitor.isRunning() && processMonitor.stateOutdated()) {
                    processMonitor.stop();
                }
                if (processMonitor.isRunning() || !table.contains(state.port, state.dbUrl)) {
                    table.put(state.port, state.dbUrl, component);
                }
            } else {
                if (processMonitor.isRunning()) {
                    processMonitor.stop();
                }
            }
        }
        return table;
    }
}
