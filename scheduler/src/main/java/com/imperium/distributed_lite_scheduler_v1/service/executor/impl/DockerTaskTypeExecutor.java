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
public class DockerTaskTypeExecutor implements TaskTypeExecutor {

    private final ProcessExecutionHelper processExecutionHelper;
    private final ExecutorConfigSupport configSupport;

    @Override
    public boolean supports(String taskType) {
        return taskType != null && "DOCKER".equalsIgnoreCase(taskType.trim());
    }

    @Override
    public ExecutionResult execute(RunSpec runSpec) throws Exception {
        JsonNode root = configSupport.parse(runSpec.getExecutorConfig());
        String image = configSupport.readText(root, "image");
        if (!StringUtils.hasText(image)) {
            return ExecutionResult.configurationError("DOCKER 任务缺少 image 配置");
        }

        Map<String, Object> params = runSpec.getParameters() != null ? runSpec.getParameters() : Map.of();
        image = ParameterTemplateResolver.resolve(image, params);

        List<String> dockerCmd = new ArrayList<>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");
        dockerCmd.add("-w");
        dockerCmd.add("/work");
        dockerCmd.add("-v");
        dockerCmd.add(runSpec.getWorkDirectory().toAbsolutePath() + ":/work");

        for (Map.Entry<String, String> env : configSupport.readStringMap(root, "env").entrySet()) {
            dockerCmd.add("-e");
            dockerCmd.add(
                    env.getKey()
                            + "="
                            + ParameterTemplateResolver.resolve(env.getValue(), params));
        }

        dockerCmd.add(image);

        List<String> containerCommand = configSupport.readStringList(root, "command");
        if (containerCommand.isEmpty() && StringUtils.hasText(runSpec.getCommand())) {
            containerCommand = List.of(runSpec.getCommand());
        }
        for (String part : containerCommand) {
            dockerCmd.add(ParameterTemplateResolver.resolve(part, params));
        }

        log.info("启动 DOCKER 子进程 taskInstanceId={} image={}", runSpec.getTaskInstanceId(), image);

        ProcessBuilder processBuilder = new ProcessBuilder(dockerCmd);
        return processExecutionHelper.execute(
                runSpec.getTaskInstanceId(),
                runSpec.getWorkDirectory(),
                runSpec.getTimeoutSeconds(),
                processBuilder);
    }
}
