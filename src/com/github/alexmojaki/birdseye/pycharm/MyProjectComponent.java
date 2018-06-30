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
import com.intellij.openapi.editor.RangeMarker;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.util.*;
import java.util.Timer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

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

    /**
     * The current (i.e. most recently retrieved when polling the server) hashes
     * and whether or not they have calls
     */
    Map<String, Boolean> functionHashes = new HashMap<>();

    /**
     * All calls currently being debugged.
     * The order of this list changes and is important.
     * When a call's panel is selected, the call moves to the beginning
     * of this list. This way activeCalls() only returns the most recent
     * call for each function.
     */
    List<Call> calls = new ArrayList<>();

    private Content callsListContent = null;
    ApiClient apiClient;

    /**
     * Whether or not the tool window is visible
     */
    private boolean isActive = false;

    Timer timer = new Timer();
    ProcessMonitor processMonitor;

    static MyProjectComponent getInstance(Project project) {
        return project.getComponent(MyProjectComponent.class);
    }

    protected MyProjectComponent(Project project) {
        super(project);
        apiClient = new ApiClient(this);
        processMonitor = new ProcessMonitor(this);
    }

    /**
     * Run checkHashes 2 seconds from now in the correct thread
     */
    private void scheduleHashCheck() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                DumbService.getInstance(myProject).smartInvokeLater(MyProjectComponent.this::checkHashes);
            }
        }, 2000);
    }

    private void checkHashes() {
        List<Editor> activeEditors = activeEditors(myProject);

        Set<String> newFunctionHashes = new HashSet<>();
        PsiRecursiveElementWalkingVisitor visitor = new PsiRecursiveElementWalkingVisitor() {
            @Override
            protected void elementFinished(PsiElement element) {
                if (!(element instanceof PyFunction)) {
                    return;
                }
                PyFunction function = (PyFunction) element;
                if (function.getNameIdentifier() == null) {
                    return;
                }

                String hash = hashFunction(function);
                newFunctionHashes.add(hash);
            }
        };

        // This function happens in the EventDispatchThread (EDT), which shouldn't be held up,
        // especially for checking the server. We only needed the EDT
        // for the list of active editors
        new Thread(() -> {
            try {
                // Collect function body hashes for all functions in all editors
                ReadAction.run(() -> {
                    for (Editor editor : activeEditors) {
                        PsiFile psiFile = PsiDocumentManager.getInstance(myProject)
                                .getPsiFile(editor.getDocument());
                        visitor.visitElement(psiFile);

                    }
                });

                // Ask the server which of those body hashes are in the database
                // Convert the result to a map the same structure as functionHashes
                Map<String, Boolean> newFunctionHashesMap =
                        Arrays.stream(apiClient.getBodyHashesPresent(newFunctionHashes))
                                .collect(Collectors.toMap(
                                        i -> i.hash,
                                        i -> i.count > 0));

                // If any changes are detected, trigger a line marker pass in the IDE
                // to refresh the birdseye icons shown by EyeLineMarkerProvider
                if (!(newFunctionHashesMap.equals(functionHashes))) {
                    functionHashes = newFunctionHashesMap;
                    DaemonCodeAnalyzer.getInstance(myProject).restart();
                }
            } finally {
                // Check again in 2 seconds, regardless of errors
                scheduleHashCheck();
            }
        }).start();
    }

    /**
     * Returns the ContentManager of the birdseye tool window,
     * creating and showing the tool window if it doesn't exist yet.
     */
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

                /** Find the only call which corresponds to this tool window content */
                private Call getCall(ContentManagerEvent event) {
                    List<Call> matching = filterToList(calls,
                            c -> c.toolWindowContent.equals(event.getContent()));
                    if (matching.isEmpty()) {
                        return null;
                    }
                    assert matching.size() == 1 : matching;
                    return matching.get(0);
                }

                /**
                 * The user closed a call panel. Clear everything related
                 * to that call.
                 */
                @Override
                public void contentRemoved(ContentManagerEvent event) {
                    Call call = getCall(event);
                    if (call != null) {
                        calls.remove(call);
                        call.hideHighlighters();
                        call.clearMemoryJustInCase();
                    }

                    // Don't leave an empty tool window open
                    if (contentManager().getContentCount() == 0) {
                        getToolWindow().hide(null);
                    }
                }

                /**
                 * The user selected a different call panel.
                 * Change what's displayed accordingly.
                 */
                @Override
                public void selectionChanged(ContentManagerEvent event) {
                    if (calls.size() > 1) {
                        calls.get(0).hideHighlighters();
                    }

                    Call call = getCall(event);
                    if (call != null) {

                        // Move call to the beginning of calls
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

    /**
     * Make the IDE update displays of highlighters, the gutter, etc.
     */
    private void updateAllThings() {
        DaemonCodeAnalyzer.getInstance(myProject).restart();
        DumbService.getInstance(myProject).smartInvokeLater(() -> {
            for (Editor editor : activeEditors(myProject)) {
                editor.getComponent().revalidate();
                editor.getComponent().repaint();
            }
        });
    }

    /**
     * Check every 0.2 seconds if the tool window is open.
     * Only show birdseye stuff in the editor when it's open.
     */
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
                            if (!calls.isEmpty()) {
                                calls.get(0).showHighlighters();
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

    @Nullable
    private ToolWindow getToolWindow() {
        return ToolWindowManager.getInstance(myProject).getToolWindow("birdseye");
    }

    @Nullable
    Call currentCall() {
        if (!isActive || calls.isEmpty()) {
            return null;
        }
        return calls.get(0);
    }

    /**
     * Returns a subset of calls which doesn't have two calls for the same
     * function. Calls with more recently selected panels get priority.
     * <p>
     * Two calls have the same function if they start at the same offset, so they can
     * have different bodies. Therefore this will behave sensibly if the user
     * debugs a function, edits it, and debugs it again, and both call panels are open.
     */
    @NotNull
    List<Call> activeCalls() {
        if (!isActive) {
            return Collections.emptyList();
        }
        Set<Integer> starts = new HashSet<>();
        List<Call> result = new ArrayList<>();
        for (Call call : calls) {
            RangeMarker rangeMarker = call.birdseyeFunction.startRangeMarker;
            if (rangeMarker.isValid() &&
                    starts.add(rangeMarker.getStartOffset())) {
                result.add(call);
            }
        }
        return result;
    }

    void setCallsListContent(Content content) {
        ContentManager contentManager = contentManager();
        if (callsListContent != null) {
            contentManager.removeContent(callsListContent, true);
        }
        contentManager.addContent(content, 0);
        callsListContent = content;
        contentManager.setSelectedContent(content);
        notNull(getToolWindow()).show(null);
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

        scheduleHashCheck();

        // Hide exception highlighters for nodes if their code changes
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(DocumentEvent event) {
                Call call = currentCall();
                if (call == null || !event.getDocument().equals(call.document())) {
                    return;
                }
                for (HideableRangeHighlighter highlighter : call.exceptionHighlighters) {
                    if (highlighter.node.isRangeInvalid()) {
                        highlighter.hide();
                    } else {
                        highlighter.show();
                    }
                }
            }
        }, myProject);
    }

    @Override
    public void projectClosed() {
        processMonitor.stop();
        MyApplicationComponent.getInstance().updateServers();
        calls.forEach(Call::clearMemoryJustInCase);
        calls.clear();
    }

    /**
     * Return the ProcessMonitor of this component or another one with
     * matching settings, favouring running monitors. That monitor is responsible
     * for running the server that this project accesses, assuming this project
     * wants to run its own server.
     */
    ProcessMonitor responsibleProcessMonitor() {
        return MyApplicationComponent.getInstance()
                .getServersBySettingsTable()
                .get(state.port, state.dbUrl)
                .processMonitor;
    }

    /**
     * Shows a warning with title and message offering to install the latest version of
     * birdseye. The message should include an 'a' tag for the user to click on.
     * onInstalled will run after the installation completes successfully.
     */
    void offerInstall(String title, String message, @Nullable Runnable onInstalled) {
        Sdk projectSdk = ProjectRootManager.getInstance(myProject).getProjectSdk();
        assert projectSdk != null;

        Function<Runnable, PyPackageManagerUI> ui = (runnable) -> new PyPackageManagerUI(myProject, projectSdk, new PyPackageManagerUI.Listener() {
            @Override
            public void started() {
            }

            @Override
            public void finished(List<ExecutionException> exceptions) {
                if (exceptions.isEmpty() && runnable != null) {
                    runnable.run();
                }
            }
        });

        final PyPackageManager packageManager = PyPackageManager.getInstance(projectSdk);

        // Install birdseye, then run onInstalled
        Runnable install = () -> ui
                .apply(onInstalled)
                .install(packageManager.parseRequirements(
                        "birdseye"),
                        Arrays.asList("--upgrade", "--upgrade-strategy", "only-if-needed"));

        NotificationListener.Adapter listener = new NotificationListener.Adapter() {
            /**
             * Runs when the user clicks the link offering to install
             */
            @Override
            protected void hyperlinkActivated(@NotNull Notification notification, @NotNull HyperlinkEvent e) {
                List<PyPackage> packages = packageManager.getPackages();
                assert packages != null;
                if (!PyPackageUtil.hasManagement(packages)) {
                    // Install management packages, then birdseye
                    ui.apply(install).installManagement();
                } else {
                    // Just install birdseye
                    install.run();
                }
            }
        };

        notify(title, message, listener, NotificationType.WARNING);
    }


}
