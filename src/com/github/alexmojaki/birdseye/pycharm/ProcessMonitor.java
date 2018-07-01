package com.github.alexmojaki.birdseye.pycharm;

import com.google.common.collect.EvictingQueue;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.jetbrains.python.packaging.PyPackage;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.packaging.PyRequirement;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class runs the birdseye server, watches the logs, reports errors, etc.
 */
class ProcessMonitor {

    private Process process;
    private MyProjectComponent projectComponent;

    /**
     * If true, the server was just stopped intentionally, so don't restart it or report an error
     */
    private volatile boolean stopped = false;

    // The settings that existed when the process started, i.e. the settings that
    // the process is currently using
    private String dbUrl;
    private String port;

    private long startTime;

    /**
     * If the process died or cannot be run, this is a short description of why,
     * displayed in the settings dialog. If the server is running fine, it's blank.
     */
    String errorMessage = "";

    private static final Pattern OUTDATED_PATTERN = Pattern.compile(
            "(birdseye is out of date). (Your version is .+, the latest is ([\\d.]+\\d))",
            Pattern.CASE_INSENSITIVE);

    ProcessMonitor(MyProjectComponent projectComponent) {
        this.projectComponent = projectComponent;
    }

    /**
     * Try to start the server process.
     */
    void start() {
        String startFailed = "Could not start birdseye server";
        String checkEventLog = ": check Event Log for details";

        assert !isRunning();

        stopped = false;

        Sdk projectSdk = ProjectRootManager.getInstance(projectComponent.getProject()).getProjectSdk();
        if (projectSdk == null) {
            errorMessage = "Waiting for a project interpreter to be found";

            // This should generally only happen when the project is starting up.
            // Check again in 1 second.
            projectComponent.timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    start();
                }
            }, 1000);
            return;
        }

        final PyPackageManager packageManager = PyPackageManager.getInstance(projectSdk);
        PyRequirement pyRequirement = packageManager
                .parseRequirements("birdseye>=0.4.2")
                .get(0);

        // Try to find out which packages are installed
        List<PyPackage> packages;
        try {
            packages = packageManager.refreshAndGetPackages(false);
        } catch (ExecutionException e) {
            projectComponent.notifyError(
                    startFailed,
                    ExceptionUtils.getStackTrace(e));
            errorMessage = startFailed + checkEventLog;
            return;
        }

        // Check if birdseye is installed. If not, show a message offering to install.
        // Once it's installed, try starting the process again.
        if (pyRequirement.match(packages) == null) {
            errorMessage = "Required version of birdseye not installed";
            projectComponent.offerInstall(
                    errorMessage,
                    "Click <a href='#'>here</a> to install/upgrade birdseye.",
                    this::start);
            return;
        }

        State state = projectComponent.state;
        port = state.port;
        dbUrl = state.dbUrl;
        final String homePath = projectSdk.getHomePath();
        GeneralCommandLine commandLine = new GeneralCommandLine(
                homePath, "-m", "birdseye", "--port", port + "");
        String charsetName = commandLine.getCharset().name();

        // Reloader must be off to allow destroying process
        commandLine.getEnvironment().put("BIRDSEYE_RELOADER", "0");

        commandLine.getEnvironment().put("BIRDSEYE_DB", dbUrl);

        startTime = System.currentTimeMillis();

        // Actually try to run the process
        try {
            process = commandLine.createProcess();
        } catch (ExecutionException e) {
            projectComponent.notifyError(
                    startFailed,
                    ExceptionUtils.getStackTrace(e));
            errorMessage = startFailed + checkEventLog;
            return;
        }

        // Success! The process has started running. Now we monitor it.
        errorMessage = "";

        // Store the 50 most recent lines from stderr, which includes
        // logging and tracebacks. Really stdout should have nothing
        EvictingQueue<String> lines = EvictingQueue.create(50);
        InputStream errorStream = process.getErrorStream();
        Scanner scanner = new Scanner(errorStream, charsetName);

        new Thread(() -> {

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    lines.add(line);

                    // birdseye will output a warning if it detects a newer version out there
                    Matcher matcher = OUTDATED_PATTERN.matcher(line);
                    if (matcher.find()) {
                        projectComponent.offerInstall(
                                matcher.group(1),
                                matcher.group(2) + ". Click <a href='#'>here</a> to upgrade.",
                                null
                        );
                    }
                }
            }

            // Whether intentionally or not, the end of the loop means the process has stopped
            // If it's because of a call to stop(), do nothing
            if (stopped) {
                return;
            }

            // Otherwise, notify of the error and maybe start again
            String title = "birdseye process ended";
            long elapsed = runningTime();
            boolean restart = elapsed > 5000;
            String output = lines.stream().collect(Collectors.joining("\n"));
            String message = restart ?
                    "The process will be restarted." :
                    "The process ended too quickly and will not be restarted";
            message += String.format(".<br>Last lines of output:<br><pre>%s</pre>",
                    output);
            projectComponent.notifyError(title, message);
            errorMessage = title + checkEventLog;

            if (restart) {
                // The process might still be alive for a bit after the output stopped
                // Actually stop it just to make sure things are in a consistent state
                // We don't care if there are errors.
                try {
                    stop();
                } catch (Exception ignored) {
                }
                start();
            }
        }).start();
    }

    long runningTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Stop the server process
     */
    void stop() {
        if (process == null) {
            return;
        }
        stopped = true;
        try {
            process.destroy();
            process.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        process = null;
        assert !isRunning();
    }

    boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * True if the server is running and the settings it's using
     * don't match the latest settings
     */
    boolean stateOutdated() {
        State state = projectComponent.state;
        return isRunning() &&
                !(Objects.equals(dbUrl, state.dbUrl) &&
                        Objects.equals(port, state.port));
    }


}
