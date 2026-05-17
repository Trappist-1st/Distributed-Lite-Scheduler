package com.imperium.distributed_lite_scheduler_v1.service.executor.impl;

import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.service.executor.ExecutionResult;
import com.imperium.distributed_lite_scheduler_v1.service.executor.RunSpec;
import com.imperium.distributed_lite_scheduler_v1.service.executor.runtime.ProcessExecutionHelper;
import com.imperium.distributed_lite_scheduler_v1.service.executor.runtime.RunningTaskRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellTaskTypeExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void executesSimpleCommand() throws Exception {
        TaskExecutorProperties properties = new TaskExecutorProperties();
        properties.setOutputMaxChars(4096);
        ProcessExecutionHelper processExecutionHelper =
                new ProcessExecutionHelper(properties, new RunningTaskRegistry());
        ShellTaskTypeExecutor executor = new ShellTaskTypeExecutor(processExecutionHelper);

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = windows ? "echo hello-dls" : "echo hello-dls";

        RunSpec runSpec = RunSpec.builder()
                .taskInstanceId(1L)
                .taskType("SHELL")
                .command(command)
                .workDirectory(tempDir)
                .timeoutSeconds(30)
                .build();

        ExecutionResult result = executor.execute(runSpec);
        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
    }
}
