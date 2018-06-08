package com.github.alexmojaki.birdseye.pycharm;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.execution.ExecutionException;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerAdapter;
import com.intellij.ui.content.ContentManagerEvent;
import com.jetbrains.python.packaging.PyPackage;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.packaging.PyPackageManagerUI;
import com.jetbrains.python.packaging.PyPackageUtil;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.psi.impl.PyFunctionImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.util.*;
import java.util.Timer;
import java.util.stream.Collectors;

import static com.github.alexmojaki.birdseye.pycharm.Utils.filterToList;

@com.intellij.openapi.components.State(name = "birdseye.xml")
public class MyProjectComponent extends AbstractProjectComponent implements PersistentStateComponent<State> {

    static final Icon BIRDSEYE_ICON = IconLoader.getIcon(
            "/com/github/alexmojaki/birdseye/pycharm/" +
                    "icons/birdseye-icon.png");

    static final Icon BIRDSEYE_EMPTY_ICON = IconLoader.getIcon(
            "/com/github/alexmojaki/birdseye/pycharm/" +
                    "icons/gray-eye-icon.png");

    State state = new State();

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    Map<String, Boolean> functionHashes = new HashMap<>();
    List<Call> calls = new ArrayList<>();
    private Content callsListContent = null;
    ApiClient apiClient;
    private boolean isActive = false;
    Timer timer = new Timer();
    ProcessMonitor processMonitor;

    static MyProjectComponent getInstance(Project project) {
        return project.getComponent(MyProjectComponent.class);
    }

    protected MyProjectComponent(Project project) {
        super(project);
        apiClient = new ApiClient(project);
        processMonitor = new ProcessMonitor(this);
    }

    private void scheduleHashCheck() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                DumbService.getInstance(myProject).smartInvokeLater(MyProjectComponent.this::checkHashes);
            }
        }, 2000);
    }

    private void checkHashes() {
        List<Editor> activeEditors = activeEditors();

        Set<String> newFunctionHashes = new HashSet<>();
        PsiRecursiveElementWalkingVisitor visitor = new PsiRecursiveElementWalkingVisitor() {
            @Override
            protected void elementFinished(PsiElement element) {
                if (!(element instanceof PyFunction)) {
                    return;
                }
                PyFunctionImpl function = (PyFunctionImpl) element;
                if (function.getNameIdentifier() == null) {
                    return;
                }

                String hash = Utils.hashFunction(function);
                newFunctionHashes.add(hash);
            }
        };

        new Thread(() -> {
            try {
                ReadAction.run(() -> {
                    for (Editor editor : activeEditors) {
                        PsiFile psiFile = PsiDocumentManager.getInstance(myProject)
                                .getPsiFile(editor.getDocument());
                        visitor.visitElement(psiFile);

                    }
                });

                Map<String, Boolean> newFunctionHashesMap =
                        Arrays.stream(apiClient.getBodyHashesPresent(newFunctionHashes))
                                .collect(Collectors.toMap(
                                        i -> i.hash,
                                        i -> i.count > 0));

                if (!(newFunctionHashesMap.equals(functionHashes))) {
                    functionHashes = newFunctionHashesMap;
                    DaemonCodeAnalyzer.getInstance(myProject).restart();
                }
            } finally {
                scheduleHashCheck();
            }
        }).start();
    }

    ContentManager contentManager() {
        ToolWindow toolWindow = getToolWindow();

        if (toolWindow == null) {
            toolWindow = ToolWindowManager.getInstance(myProject).registerToolWindow(
                    "birdseye",
                    true,
                    ToolWindowAnchor.BOTTOM
            );
            toolWindow.setIcon(BIRDSEYE_ICON);
            toolWindow.getContentManager().addContentManagerListener(new ContentManagerAdapter() {
                private Call getCall(Content content) {
                    List<Call> matching = filterToList(calls,
                            c -> c.toolWindowContent.equals(content));
                    if (matching.isEmpty()) {
                        return null;
                    }
                    assert matching.size() == 1 : matching;
                    return matching.get(0);
                }

                private Call getCall(ContentManagerEvent event) {
                    return getCall(event.getContent());
                }

                @Override
                public void contentRemoved(ContentManagerEvent event) {
                    Call call = getCall(event);
                    calls.remove(call);
                    if (call != null) {
                        call.hideHighlighters();
                    }
                    if (contentManager().getContentCount() == 0) {
                        getToolWindow().hide(null);
                    }
                }

                @Override
                public void selectionChanged(ContentManagerEvent event) {
                    if (calls.size() > 1) {
                        calls.get(0).hideHighlighters();
                    }

                    Call call = getCall(event);
                    if (call != null) {
                        calls.remove(call);
                        calls.add(0, call);
                        call.showHighlighters();
                    }

                    updateAllThings();
                }
            });

            scheduleActiveCheck(toolWindow);
        }
        toolWindow.show(null);
        return toolWindow.getContentManager();
    }

    private void updateAllThings() {
        DaemonCodeAnalyzer.getInstance(myProject).restart();
        DumbService.getInstance(myProject).smartInvokeLater(() -> {
            for (Editor editor : activeEditors()) {
                editor.getComponent().revalidate();
                editor.getComponent().repaint();
            }
        });
    }

    private void scheduleActiveCheck(ToolWindow toolWindow) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (isActive == toolWindow.isVisible()) {
                        return;
                    }
                    isActive = toolWindow.isVisible();
                    DumbService.getInstance(myProject).smartInvokeLater(() -> {
                        if (isActive) {
                            for (Call call : activeCalls()) {
                                call.showHighlighters();
                            }
                        } else {
                            for (Call call : calls) {
                                call.hideHighlighters();
                            }
                        }
                    });
                    updateAllThings();
                } finally {
                    scheduleActiveCheck(toolWindow);
                }
            }
        }, 200);

    }

    private ToolWindow getToolWindow() {
        return ToolWindowManager.getInstance(myProject).getToolWindow("birdseye");
    }

    List<Call> activeCalls() {
        List<Call> result = new ArrayList<>();
        Set<Range> ranges = new HashSet<>();

        for (Call call : calls()) {
            if (ranges.add(call.birdseyeFunction.fullRange())) {
                result.add(call);
            }
        }
        return result;
    }

    List<Call> calls() {
        if (!isActive) {
            return Collections.emptyList();
        }
        return calls;
    }

    private List<Editor> activeEditors() {
        return Utils.activeEditors(myProject);
    }

    void setCallsListContent(Content content) {
        ContentManager contentManager = contentManager();
        if (callsListContent != null) {
            contentManager.removeContent(callsListContent, true);
        }
        contentManager.addContent(content, 0);
        callsListContent = content;
        contentManager.setSelectedContent(content);
        getToolWindow().show(null);
    }

    void notifyError(String title, String message) {
        notify(title, message, null, NotificationType.ERROR);
    }

    private void notify(String title, String message, NotificationListener listener, NotificationType type) {
        Notifications.Bus.notify(new Notification(
                        "birdseye",
                        MyProjectComponent.BIRDSEYE_ICON,
                        title,
                        null,
                        message,
                        type,
                        listener),
                myProject);
    }

    Project getProject() {
        return myProject;
    }

    @Override
    public void projectOpened() {
        MyApplicationComponent.getInstance().updateServers();

        // TODO wait for server to start
        scheduleHashCheck();

        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(DocumentEvent event) {
                for (Call call : calls) {
                    if (!event.getDocument().equals(call.document())) {
                        continue;
                    }
                    for (Call.Node node : new ArrayList<>(call.panel.selectedNodes)) {
                        if (node.isRangeInvalid()) {
                            call.panel.toggleSelectedNode(node);
                        }
                    }
                }
            }
        }, myProject);
    }

    @Override
    public void projectClosed() {
        processMonitor.stop();
        MyApplicationComponent.getInstance().updateServers();
    }

    ProcessMonitor responsibleProcessMonitor() {
        return MyApplicationComponent.getInstance()
                .getServersBySettingsTable()
                .get(state.port, state.dbUrl)
                .processMonitor;
    }

    void offerInstall(String title, String message, String requirement, Runnable onInstalled) {
        NotificationListener.Adapter listener = new NotificationListener.Adapter() {
            @Override
            protected void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent e) {
                Sdk projectSdk = ProjectRootManager.getInstance(myProject).getProjectSdk();
                assert projectSdk != null;
                final PyPackageManager packageManager = PyPackageManager.getInstance(projectSdk);
                Runnable install = () -> {
                    PyPackageManagerUI ui = new PyPackageManagerUI(myProject, projectSdk, new PyPackageManagerUI.Listener() {
                        @Override
                        public void started() {
                        }

                        @Override
                        public void finished(List<ExecutionException> exceptions) {
                            if (exceptions.isEmpty()) {
                                onInstalled.run();
                            }
                        }
                    });
                    ui.install(packageManager.parseRequirements(requirement), Collections.emptyList());
                };
                List<PyPackage> packages = packageManager.getPackages();
                assert packages != null;
                if (!PyPackageUtil.hasManagement(packages)) {
                    PyPackageManagerUI ui = new PyPackageManagerUI(myProject, projectSdk, new PyPackageManagerUI.Listener() {
                        @Override
                        public void started() {
                        }

                        @Override
                        public void finished(List<ExecutionException> exceptions) {
                            if (exceptions.isEmpty()) {
                                install.run();
                            }
                        }
                    });
                    ui.installManagement();
                } else {
                    install.run();
                }
            }
        };
        notify(title, message, listener, NotificationType.WARNING);
    }


}
