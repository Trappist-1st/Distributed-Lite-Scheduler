package com.imperium.distributed_lite_scheduler_v1.service.executor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.imperium.distributed_lite_scheduler_v1.service.executor.ExecutionResult;
import com.imperium.distributed_lite_scheduler_v1.service.executor.ExecutorConfigSupport;
import com.imperium.distributed_lite_scheduler_v1.service.executor.ParameterTemplateResolver;
import com.imperium.distributed_lite_scheduler_v1.service.executor.RunSpec;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskTypeExecutor;
import com.imperium.distributed_lite_scheduler_v1.service.executor.runtime.ProcessExecutionHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonTaskTypeExecutor implements TaskTypeExecutor {

    private final ProcessExecutionHelper processExecutionHelper;
    private final ExecutorConfigSupport configSupport;

    @Override
    public boolean supports(String taskType) {
        return taskType != null && "PYTHON".equalsIgnoreCase(taskType.trim());
    }

    @Override
    public ExecutionResult execute(RunSpec runSpec) throws Exception {
        JsonNode root = configSupport.parse(runSpec.getExecutorConfig());
        String script = configSupport.readText(root, "script");
        if (!StringUtils.hasText(script) && StringUtils.hasText(runSpec.getCommand())) {
            script = runSpec.getCommand();
        }
        if (!StringUtils.hasText(script)) {
            return ExecutionResult.configurationError("PYTHON 任务缺少 script 配置");
        }

        Map<String, Object> params = runSpec.getParameters() != null ? runSpec.getParameters() : Map.of();
        script = ParameterTemplateResolver.resolve(script, params);

        List<String> command = new ArrayList<>();
        command.add(resolvePythonExecutable(root));
        command.add(script);

        for (String arg : configSupport.readStringList(root, "args")) {
            command.add(ParameterTemplateResolver.resolve(arg, params));
        }

        log.info("启动 PYTHON 子进程 taskInstanceId={} command={}", runSpec.getTaskInstanceId(), command);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        return processExecutionHelper.execute(
                runSpec.getTaskInstanceId(),
                runSpec.getWorkDirectory(),
                runSpec.getTimeoutSeconds(),
                processBuilder);
    }

    private String resolvePythonExecutable(JsonNode root) {
        String configured = configSupport.readText(root, "python");
        return StringUtils.hasText(configured) ? configured.trim() : "python";
    }
}
