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

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;
import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

/**
 * This class primarily manages all the projects' ProcessMonitors.
 */
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

    /**
     * Multiple projects with the same settings that want to run their own servers
     * only need to have one server (and in fact can only have one server, to avoid
     * port conflicts). As settings change and projects are opened and closed,
     * this ensures that each project gets one server.
     */
    void updateServers() {
        Table<String, String, MyProjectComponent> table = getServersBySettingsTable();
        for (String port : table.rowKeySet()) {
            Map<String, MyProjectComponent> forPort = table.row(port);
            for (MyProjectComponent component : forPort.values()) {
                if (forPort.size() > 1) {
                    List<String> items = mapToList(
                            forPort.values(),
                            c -> String.format("%s (<code>%s</code>)",
                                    escapeHtml(c.getProject().getName()),
                                    escapeHtml(component.state.dbUrl)));

                    String title = "Multiple projects running birdseye on the same port with different database URLs";
                    String message = htmlList("ul", items);
                    component.notifyError(title, message);
                    component.processMonitor.errorMessage = tag("html",
                            title + " <br> " + message);

                } else {
                    ProcessMonitor processMonitor = component.processMonitor;
                    if (!processMonitor.isRunning()) {
                        processMonitor.start();
                    }
                    // This should be ensured by getServersBySettingsTable
                    assert !processMonitor.stateOutdated();
                }
            }
        }
    }

    /**
     * This returns a table mapping the two settings which define a running server
     * (the port and database URL) to a MyProjectComponent with those settings
     * and which wants to run its own server. There may be several projects
     * for a combination of settings but this only returns one, which is deemed
     * 'responsible' for running the server (see MyProjectComponent.responsibleProcessMonitor).
     * Preference is given to servers that are actually running already.
     * Any servers found to be running which shouldn't be are stopped.
     */
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
