package com.imperium.distributed_lite_scheduler_v1.service.executor.runtime;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.service.executor.ExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行通用逻辑：注册可取消句柄、采集输出、超时终止。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessExecutionHelper {

    private final TaskExecutorProperties properties;
    private final RunningTaskRegistry runningTaskRegistry;

    public ExecutionResult execute(
            Long taskInstanceId, Path workDirectory, Integer timeoutSeconds, ProcessBuilder processBuilder)
            throws Exception {
        Files.createDirectories(workDirectory);
        processBuilder.directory(workDirectory.toFile());
        processBuilder.redirectErrorStream(false);

        Process process = processBuilder.start();
        runningTaskRegistry.register(taskInstanceId, new ProcessRunningTaskHandle(taskInstanceId, process));

        try {
            long waitSeconds = timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : Long.MAX_VALUE;

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Charset charset = Charset.defaultCharset();

            Thread stdoutReader = readStream(process.getInputStream(), stdout, charset);
            Thread stderrReader = readStream(process.getErrorStream(), stderr, charset);
            stdoutReader.start();
            stderrReader.start();

            boolean finished;
            if (waitSeconds == Long.MAX_VALUE) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(waitSeconds, TimeUnit.SECONDS);
            }

            stdoutReader.join(5_000);
            stderrReader.join(5_000);

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
                    if (target.length() < properties.getOutputMaxChars()) {
                        if (!target.isEmpty()) {
                            target.append(System.lineSeparator());
                        }
                        target.append(line);
                    }
                }
            } catch (Exception e) {
                log.debug("读取进程输出异常", e);
            }
        }, "process-exec-stream");
        thread.setDaemon(true);
        return thread;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int max = properties.getOutputMaxChars();
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
