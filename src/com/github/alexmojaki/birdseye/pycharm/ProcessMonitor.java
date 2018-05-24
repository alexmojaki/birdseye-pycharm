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

class ProcessMonitor {

    private Process process;
    private MyProjectComponent projectComponent;
    private volatile boolean stopped = false;

    private String dbUrl;
    private String port;
    private long startTime;

    String errorMessage = "";

    private static final Pattern OUTDATED_PATTERN = Pattern.compile(
            "(birdseye is out of date). (Your version is .+, the latest is ([\\d.]+\\d))",
            Pattern.CASE_INSENSITIVE);

    ProcessMonitor(MyProjectComponent projectComponent) {
        this.projectComponent = projectComponent;
    }

    void start() {
        String startFailed = "Could not start birdseye server";
        String checkEventLog = ": check Event Log for details";

        assert !isRunning();

        stopped = false;

        Sdk projectSdk = ProjectRootManager.getInstance(projectComponent.getProject()).getProjectSdk();
        if (projectSdk == null) {
            errorMessage = "Waiting for a project interpreter to be found";
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
                .parseRequirements("birdseye")
                .get(0);
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

        if (pyRequirement.match(packages) == null) {
            errorMessage = "Required version of birdseye not installed";
            projectComponent.offerInstall(
                    errorMessage,
                    "Click <a href='#'>here</a> to install/upgrade birdseye.",
                    "birdseye",
                    this::start);
            return;
        }

        State state = projectComponent.state;
        port = state.port;
        dbUrl = state.dbUrl;
        final String homePath = projectSdk.getHomePath();
        GeneralCommandLine commandLine = new GeneralCommandLine(
                homePath, "-m", "birdseye", "--port", port + "")
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE);
        String charsetName = commandLine.getCharset().name();

        startTime = System.currentTimeMillis();

        if (!dbUrl.isEmpty()) {
            commandLine.getEnvironment().put("BIRDSEYE_DB", dbUrl);
        }
        try {
            process = commandLine.createProcess();
        } catch (ExecutionException e) {
            projectComponent.notifyError(
                    startFailed,
                    ExceptionUtils.getStackTrace(e));
            errorMessage = startFailed + checkEventLog;
            return;
        }
        errorMessage = "";
        EvictingQueue<String> lines = EvictingQueue.create(50);
        InputStream errorStream = process.getErrorStream();
        Scanner scanner = new Scanner(errorStream, charsetName);

        new Thread(() -> {

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                    Matcher matcher = OUTDATED_PATTERN.matcher(line);
                    if (matcher.find()) {
                        projectComponent.offerInstall(
                                matcher.group(1),
                                matcher.group(2) + ". Click <a href='#'>here</a> to upgrade.",
                                "birdseye==" + matcher.group(3),
                                () -> {
                                }
                        );
                    }
                }
            }

            if (stopped) {
                return;
            }

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
                start();
            }
        }).start();
    }

    long runningTime() {
        return System.currentTimeMillis() - startTime;
    }

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

    boolean stateOutdated() {
        State state = projectComponent.state;
        return isRunning() &&
                !(Objects.equals(dbUrl, state.dbUrl) &&
                        Objects.equals(port, state.port));
    }


}
