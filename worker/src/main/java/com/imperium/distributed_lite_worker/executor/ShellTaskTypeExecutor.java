package com.imperium.distributed_lite_worker.executor;

import com.imperium.distributed_lite_worker.runtime.ProcessExecutionHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ShellTaskTypeExecutor implements WorkerTaskTypeExecutor {

    private final ProcessExecutionHelper processExecutionHelper;

    @Override
    public boolean supports(String taskType) {
        return taskType != null && "SHELL".equalsIgnoreCase(taskType.trim());
    }

    @Override
    public ExecutionResult execute(LocalRunSpec runSpec) throws Exception {
        if (!StringUtils.hasText(runSpec.getCommand())) {
            return ExecutionResult.configurationError("SHELL 任务缺少 command");
        }
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd.exe", "/c", runSpec.getCommand())
                : new ProcessBuilder("sh", "-c", runSpec.getCommand());
        return processExecutionHelper.execute(
                runSpec.getTaskInstanceId(),
                runSpec.getWorkDirectory(),
                runSpec.getTimeoutSeconds(),
                pb);
    }
}
