package com.imperium.distributed_lite_scheduler_v1.service.executor.impl;

import com.imperium.distributed_lite_scheduler_v1.service.executor.ExecutionResult;
import com.imperium.distributed_lite_scheduler_v1.service.executor.RunSpec;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskTypeExecutor;
import com.imperium.distributed_lite_scheduler_v1.service.executor.runtime.ProcessExecutionHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShellTaskTypeExecutor implements TaskTypeExecutor {

    private final ProcessExecutionHelper processExecutionHelper;

    @Override
    public boolean supports(String taskType) {
        return taskType != null && "SHELL".equalsIgnoreCase(taskType.trim());
    }

    @Override
    public ExecutionResult execute(RunSpec runSpec) throws Exception {
        if (!StringUtils.hasText(runSpec.getCommand())) {
            return ExecutionResult.configurationError("SHELL 任务缺少 command 配置");
        }

        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder processBuilder = windows
                ? new ProcessBuilder("cmd.exe", "/c", runSpec.getCommand())
                : new ProcessBuilder("sh", "-c", runSpec.getCommand());

        log.info(
                "启动 SHELL 子进程 taskInstanceId={} command={}",
                runSpec.getTaskInstanceId(),
                runSpec.getCommand());

        return processExecutionHelper.execute(
                runSpec.getTaskInstanceId(),
                runSpec.getWorkDirectory(),
                runSpec.getTimeoutSeconds(),
                processBuilder);
    }
}
