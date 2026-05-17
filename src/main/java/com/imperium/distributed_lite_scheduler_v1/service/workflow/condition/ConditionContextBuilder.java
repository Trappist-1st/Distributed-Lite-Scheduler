package com.imperium.distributed_lite_scheduler_v1.service.workflow.condition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition.ConditionContext;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition.TaskResult;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 从运行中/已结束的工作流实例组装 {@link ConditionContext}（P4-4）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConditionContextBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    private final ObjectMapper objectMapper;

    /**
     * 为指定工作流实例构建一份可用于 SpEL 的上下文快照。
     */
    public ConditionContext buildContext(Long workflowInstanceId) {
        if (workflowInstanceId == null) {
            throw new IllegalArgumentException("workflowInstanceId must not be null");
        }

        WorkflowInstance instance = workflowInstanceMapper.selectById(workflowInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("工作流实例不存在: " + workflowInstanceId);
        }

        ConditionContext ctx = new ConditionContext();
        ctx.setTasks(collectTerminalTaskResults(workflowInstanceId));
        ctx.setContext(parseInstanceContext(instance.getContextJson()));
        ctx.setSystem(buildSystem(instance));
        return ctx;
    }

    private Map<String, Object> parseInstanceContext(String contextJson) {
        if (!StringUtils.hasText(contextJson)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m =
                    objectMapper.readValue(contextJson.trim(), MAP_TYPE);
            return m != null ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("解析实例 context_json 失败，将作为空上下文处理 workflowInstanceJsonLen={}", Optional.of(contextJson.length()), e);
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> buildSystem(WorkflowInstance instance) {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("workflowInstanceId", instance.getId());
        system.put("currentTime", LocalDateTime.now());
        system.put("workflowId", instance.getWorkflowId());
        system.put("tenantId", instance.getTenantId());
        return system;
    }

    /**
     * 仅聚合已<strong>结束</strong>的节点结果；未结束的节点不出现在 {@code tasks} 中，
     * 便于表达式用存在性判断是否已跑出结果。
     */
    private Map<String, TaskResult> collectTerminalTaskResults(Long workflowInstanceId) {
        List<WorkflowTaskInstance> rows =
                workflowTaskInstanceMapper.selectByInstanceId(workflowInstanceId);
        Map<String, TaskResult> tasks = new HashMap<>();

        for (WorkflowTaskInstance wti : rows) {
            TaskInstanceStatus status = parseStoredStatus(wti.getStatus());
            if (status == null || !status.isTerminal()) {
                continue;
            }
            TaskResult tr = new TaskResult();
            tr.setStatus(status.getCode());
            tr.setExitCode(wti.getExitCode());
            if (wti.getDurationSeconds() != null) {
                tr.setDurationSeconds(Long.valueOf(wti.getDurationSeconds().longValue()));
            }
            tr.setOutput(parseOutputMap(wti.getOutput()));
            tasks.put(wti.getTaskName(), tr);
        }
        return tasks;
    }

    private TaskInstanceStatus parseStoredStatus(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        try {
            return TaskInstanceStatus.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> parseOutputMap(String output) {
        if (!StringUtils.hasText(output)) {
            return new LinkedHashMap<>();
        }
        String raw = output.trim();
        try {
            if (raw.startsWith("{")) {
                Map<String, Object> parsed = objectMapper.readValue(raw, MAP_TYPE);
                return parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
            }
        } catch (Exception e) {
            log.trace("workflow_task_instance.output 非 JSON object，将作为 raw 键保留 taskBodyLen={}",
                    Optional.of(raw.length()), e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("raw", raw);
        return body;
    }
}
