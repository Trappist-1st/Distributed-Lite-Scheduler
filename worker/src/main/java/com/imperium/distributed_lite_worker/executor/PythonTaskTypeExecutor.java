package com.imperium.distributed_lite_worker.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.imperium.distributed_lite_worker.runtime.ProcessExecutionHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PythonTaskTypeExecutor implements WorkerTaskTypeExecutor {

    private final ProcessExecutionHelper processExecutionHelper;
    private final ExecutorConfigSupport configSupport;

    @Override
    public boolean supports(String taskType) {
        return taskType != null && "PYTHON".equalsIgnoreCase(taskType.trim());
    }

    @Override
    public ExecutionResult execute(LocalRunSpec runSpec) throws Exception {
        JsonNode root = configSupport.parse(runSpec.getExecutorConfig());
        String script = configSupport.readText(root, "script");
        if (!StringUtils.hasText(script) && StringUtils.hasText(runSpec.getCommand())) {
            script = runSpec.getCommand();
        }
        if (!StringUtils.hasText(script)) {
            return ExecutionResult.configurationError("PYTHON 任务缺少 script");
        }
        Map<String, Object> params = runSpec.getParameters() != null ? runSpec.getParameters() : Map.of();
        script = ParameterTemplateResolver.resolve(script, params);

        List<String> command = new ArrayList<>();
        command.add(StringUtils.hasText(configSupport.readText(root, "python"))
                ? configSupport.readText(root, "python").trim()
                : "python");
        command.add(script);
        for (String arg : configSupport.readStringList(root, "args")) {
            command.add(ParameterTemplateResolver.resolve(arg, params));
        }

        return processExecutionHelper.execute(
                runSpec.getTaskInstanceId(),
                runSpec.getWorkDirectory(),
                runSpec.getTimeoutSeconds(),
                new ProcessBuilder(command));
    }
}
