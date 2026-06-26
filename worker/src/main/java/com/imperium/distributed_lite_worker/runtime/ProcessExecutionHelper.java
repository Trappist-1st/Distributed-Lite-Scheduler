package com.imperium.distributed_lite_worker.runtime;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import com.imperium.distributed_lite_worker.executor.ExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ProcessExecutionHelper {

    private final WorkerProperties properties;
    private final RunningTaskRegistry runningTaskRegistry;

    public ExecutionResult execute(
            Long taskInstanceId, Path workDirectory, Integer timeoutSeconds, ProcessBuilder processBuilder)
            throws Exception {
        Files.createDirectories(workDirectory);
        processBuilder.directory(workDirectory.toFile());
        processBuilder.redirectErrorStream(false);

        Process process = processBuilder.start();
        ProcessRunningTaskHandle handle = new ProcessRunningTaskHandle(taskInstanceId, process);
        if (!runningTaskRegistry.tryRegister(taskInstanceId, handle)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return ExecutionResult.failure(
                    -1, null, null, "duplicate execution rejected: taskInstanceId already running");
        }

        try {
            long waitSeconds = timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : Long.MAX_VALUE;
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Charset charset = Charset.defaultCharset();

            Thread outThread = readStream(process.getInputStream(), stdout, charset);
            Thread errThread = readStream(process.getErrorStream(), stderr, charset);
            outThread.start();
            errThread.start();

            boolean finished;
            if (waitSeconds == Long.MAX_VALUE) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(waitSeconds, TimeUnit.SECONDS);
            }

            outThread.join(5_000);
            errThread.join(5_000);

            String out = truncate(stdout.toString());
            String err = truncate(stderr.toString());

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return ExecutionResult.timedOut("任务执行超时（超过 " + waitSeconds + " 秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ExecutionResult.success(exitCode, out, err);
            }
            String message = err != null && !err.isBlank() ? err : ("进程退出码 " + exitCode);
            return ExecutionResult.failure(exitCode, out, err, truncate(message));
        } finally {
            runningTaskRegistry.unregister(taskInstanceId);
        }
    }

    private Thread readStream(java.io.InputStream stream, StringBuilder target, Charset charset) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (target.length() < properties.getExecutor().getOutputMaxChars()) {
                        if (!target.isEmpty()) {
                            target.append(System.lineSeparator());
                        }
                        target.append(line);
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }, "worker-process-stream");
        thread.setDaemon(true);
        return thread;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int max = properties.getExecutor().getOutputMaxChars();
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
