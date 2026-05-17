package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.LayerProgressVO;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowProgressVO;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowTaskInstanceVO;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowInstanceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Tag(name = "工作流监控", description = "实例进度、任务列表、执行计划快照与时间线（只读）")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/workflow/instance")
@Slf4j
public class WorkflowInstanceMonitorController {

    @Autowired
    private WorkflowInstanceService workflowInstanceService;

    @Autowired
    private WorkflowTaskInstanceMapper workflowTaskInstanceMapper;

    @Autowired
    private WorkflowMapper workflowMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Operation(summary = "获取实例实时进度", description = "含分层进度与预估剩余时间")
    @GetMapping("/{id}/progress")
    public Result<WorkflowProgressVO> getProgress(@PathVariable Long id) {
        log.info("获取工作流实例进度, id={}", id);
        WorkflowInstance instance = workflowInstanceService.getWorkflowInstance(id);
        if (instance == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流实例不存在");
        }
        List<WorkflowTaskInstance> tasks = workflowTaskInstanceMapper.selectByInstanceId(id);
        WorkflowProgressVO vo = buildProgressVo(instance, tasks);
        return Result.success(vo);
    }

    @Operation(summary = "获取实例任务列表", description = "可按状态、层级过滤")
    @GetMapping("/{id}/tasks")
    public Result<List<WorkflowTaskInstanceVO>> getTasks(
            @PathVariable Long id,
            @Parameter(description = "任务状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED")
                    @RequestParam(required = false)
                    String status,
            @Parameter(description = "拓扑层索引") @RequestParam(required = false) Integer layerIndex) {
        log.info("获取工作流实例任务列表, id={}, status={}, layerIndex={}", id, status, layerIndex);
        if (workflowInstanceService.getWorkflowInstance(id) == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流实例不存在");
        }
        List<WorkflowTaskInstance> tasks = workflowTaskInstanceMapper.selectByInstanceId(id);
        String normalizedStatus = normalizeTaskStatusParam(status);
        List<WorkflowTaskInstanceVO> rows =
                tasks.stream()
                        .filter(t -> normalizedStatus == null || normalizedStatus.equalsIgnoreCase(t.getStatus()))
                        .filter(t -> layerIndex == null || Objects.equals(t.getLayerIndex(), layerIndex))
                        .sorted(Comparator.comparing(WorkflowTaskInstance::getLayerIndex, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(WorkflowTaskInstance::getTaskName, Comparator.nullsLast(String::compareTo)))
                        .map(this::toTaskVo)
                        .collect(Collectors.toList());
        return Result.success(rows);
    }

    @Operation(summary = "获取工作流任务节点详情")
    @GetMapping("/{id}/tasks/{taskId}")
    public Result<WorkflowTaskInstanceVO> getTaskDetail(
            @PathVariable Long id,
            @Parameter(description = "workflow_task_instance 表主键") @PathVariable Long taskId) {
        log.info("获取工作流任务实例详情, id={}, taskId={}", id, taskId);
        if (workflowInstanceService.getWorkflowInstance(id) == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流实例不存在");
        }
        WorkflowTaskInstance task = workflowTaskInstanceMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getWorkflowInstanceId(), id)) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流任务实例不存在");
        }
        return Result.success(toTaskVo(task));
    }

    @Operation(summary = "获取执行计划快照", description = "实例创建时固化的分层拓扑 JSON")
    @GetMapping("/{id}/execution-plan")
    public Result<WorkflowExecutionPlan> getExecutionPlan(@PathVariable Long id) {
        log.info("获取工作流实例执行计划, id={}", id);
        WorkflowInstance instance = workflowInstanceService.getWorkflowInstance(id);
        if (instance == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流实例不存在");
        }
        if (!StringUtils.hasText(instance.getExecutionPlan())) {
            return Result.failure(ResultCode.BAD_REQUEST, "该实例未包含执行计划快照");
        }
        try {
            WorkflowExecutionPlan plan =
                    objectMapper.readValue(instance.getExecutionPlan(), WorkflowExecutionPlan.class);
            return Result.success(plan);
        } catch (Exception e) {
            log.error("解析执行计划JSON失败 instanceId={}", id, e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "执行计划解析失败");
        }
    }

    @Operation(summary = "获取执行时间线", description = "甘特图/时间轴可视化数据")
    @GetMapping("/{id}/timeline")
    public Result<Map<String, Object>> getTimeline(@PathVariable Long id) {
        log.info("获取工作流实例执行时间线, id={}", id);
        WorkflowInstance instance = workflowInstanceService.getWorkflowInstance(id);
        if (instance == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流实例不存在");
        }
        List<WorkflowTaskInstance> tasks = workflowTaskInstanceMapper.selectByInstanceId(id);
        tasks.sort(
                Comparator.comparing(WorkflowTaskInstance::getLayerIndex, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(WorkflowTaskInstance::getStartTime, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(WorkflowTaskInstance::getTaskName, Comparator.nullsLast(String::compareTo)));

        List<Map<String, Object>> segments = new ArrayList<>(tasks.size());
        for (WorkflowTaskInstance t : tasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskName", t.getTaskName());
            row.put("status", t.getStatus());
            row.put("layerIndex", t.getLayerIndex());
            row.put("startTime", t.getStartTime());
            row.put("endTime", t.getEndTime());
            row.put("durationSeconds", t.getDurationSeconds());
            segments.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", id);
        body.put("workflowStartTime", instance.getStartTime());
        body.put("workflowEndTime", instance.getEndTime());
        body.put("tasks", segments);
        return Result.success(body);
    }

    private WorkflowProgressVO buildProgressVo(WorkflowInstance instance, List<WorkflowTaskInstance> tasks) {
        WorkflowProgressVO vo = new WorkflowProgressVO();
        vo.setInstanceId(instance.getId());
        vo.setInstanceName(instance.getInstanceCode());
        vo.setStartTime(instance.getStartTime());
        vo.setEndTime(instance.getEndTime());
        if (instance.getStartTime() != null && instance.getEndTime() != null) {
            vo.setDurationSeconds((int) Duration.between(instance.getStartTime(), instance.getEndTime()).getSeconds());
        } else if (instance.getDurationMs() != null) {
            vo.setDurationSeconds((int) (instance.getDurationMs() / 1000));
        }

        WorkflowInstanceStatus wf = parseWorkflowInstanceStatusSafe(instance.getStatus());
        vo.setStatus(wf);

        int total = tasks.size();
        int success = 0;
        int failed = 0;
        int skipped = 0;
        int running = 0;
        int pending = 0;
        for (WorkflowTaskInstance t : tasks) {
            TaskInstanceStatus s = parseTaskStatusSafe(t.getStatus());
            if (s == null) {
                continue;
            }
            if (s == TaskInstanceStatus.SUCCESS) {
                success++;
            } else if (s == TaskInstanceStatus.FAILED) {
                failed++;
            } else if (s == TaskInstanceStatus.SKIPPED) {
                skipped++;
            } else if (s == TaskInstanceStatus.RUNNING) {
                running++;
            } else if (s == TaskInstanceStatus.PENDING) {
                pending++;
            }
        }

        vo.setTotalTasks(total);
        vo.setCompletedTasks(success + failed + skipped);
        vo.setFailedTasks(failed);
        vo.setSkippedTasks(skipped);
        vo.setRunningTasks(running);
        vo.setPendingTasks(pending);
        vo.setProgress(total > 0 ? 100.0 * (success + failed + skipped) / total : 0.0);

        vo.setEstimatedRemainingSeconds(estimateRemainingSeconds(instance, tasks, success + failed + skipped));

        if (instance.getWorkflowId() != null) {
            Workflow def = workflowMapper.selectById(instance.getWorkflowId());
            if (def != null) {
                vo.setWorkflowName(def.getWorkflowName());
            }
        }

        vo.setLayers(buildLayerProgress(tasks));
        return vo;
    }

    private List<LayerProgressVO> buildLayerProgress(List<WorkflowTaskInstance> tasks) {
        Map<Integer, List<WorkflowTaskInstance>> byLayer = new TreeMap<>();
        for (WorkflowTaskInstance t : tasks) {
            int layer = t.getLayerIndex() == null ? -1 : t.getLayerIndex();
            byLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(t);
        }
        List<LayerProgressVO> layers = new ArrayList<>();
        for (Map.Entry<Integer, List<WorkflowTaskInstance>> e : byLayer.entrySet()) {
            LayerProgressVO lp = new LayerProgressVO();
            int idx = e.getKey();
            lp.setLayerIndex(idx < 0 ? null : idx);
            lp.setLayerName(idx < 0 ? "Layer ?" : "Layer " + idx);
            List<WorkflowTaskInstance> layerTasks = e.getValue();
            int lt = layerTasks.size();
            int success = 0;
            int failed = 0;
            int skipped = 0;
            int running = 0;
            int pend = 0;
            for (WorkflowTaskInstance t : layerTasks) {
                TaskInstanceStatus s = parseTaskStatusSafe(t.getStatus());
                if (s == TaskInstanceStatus.SUCCESS) {
                    success++;
                } else if (s == TaskInstanceStatus.FAILED) {
                    failed++;
                } else if (s == TaskInstanceStatus.SKIPPED) {
                    skipped++;
                } else if (s == TaskInstanceStatus.RUNNING) {
                    running++;
                } else if (s == TaskInstanceStatus.PENDING) {
                    pend++;
                }
            }
            lp.setTotalTasks(lt);
            lp.setCompletedTasks(success);
            lp.setFailedTasks(failed);
            lp.setSkippedTasks(skipped);
            lp.setRunningTasks(running);
            lp.setPendingTasks(pend);
            int terminal = success + failed + skipped;
            lp.setProgress(lt > 0 ? 100.0 * terminal / lt : 0.0);
            lp.setStatus(resolveLayerStatus(running, pend, failed, terminal, lt));
            layers.add(lp);
        }
        return layers;
    }

    private static String resolveLayerStatus(int running, int pending, int failed, int terminal, int total) {
        if (running > 0) {
            return "RUNNING";
        }
        if (terminal >= total) {
            return failed > 0 ? "FAILED" : "COMPLETED";
        }
        if (pending > 0 && terminal == 0) {
            return "PENDING";
        }
        return "RUNNING";
    }

    private Integer estimateRemainingSeconds(
            WorkflowInstance instance, List<WorkflowTaskInstance> tasks, int finishedCount) {
        if (finishedCount <= 0 || instance.getStartTime() == null) {
            return null;
        }
        int pendingLike = 0;
        for (WorkflowTaskInstance t : tasks) {
            TaskInstanceStatus s = parseTaskStatusSafe(t.getStatus());
            if (s == TaskInstanceStatus.PENDING || s == TaskInstanceStatus.RUNNING) {
                pendingLike++;
            }
        }
        if (pendingLike <= 0) {
            return 0;
        }
        long elapsedSec = Duration.between(instance.getStartTime(), LocalDateTime.now()).getSeconds();
        if (elapsedSec <= 0) {
            return null;
        }
        double avg = elapsedSec / (double) finishedCount;
        return (int) Math.round(avg * pendingLike);
    }

    private WorkflowTaskInstanceVO toTaskVo(WorkflowTaskInstance t) {
        WorkflowTaskInstanceVO vo = new WorkflowTaskInstanceVO();
        vo.setId(t.getId());
        vo.setWorkflowInstanceId(t.getWorkflowInstanceId());
        vo.setTaskName(t.getTaskName());
        vo.setLayerIndex(t.getLayerIndex());
        vo.setRetryCount(t.getRetryCount());
        vo.setTaskInstanceId(t.getTaskInstanceId());
        vo.setStartTime(formatIso(t.getStartTime()));
        vo.setEndTime(formatIso(t.getEndTime()));
        vo.setDurationSeconds(t.getDurationSeconds());
        vo.setExitCode(t.getExitCode());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setCreatedAt(formatIso(t.getCreatedAt()));
        vo.setUpdatedAt(formatIso(t.getUpdatedAt()));
        vo.setStatus(t.getStatus());
        TaskInstanceStatus st = parseTaskStatusSafe(t.getStatus());
        vo.setStatusDescription(st != null ? st.getDescription() : null);
        if (t.getOutput() != null) {
            String o = t.getOutput();
            vo.setOutputSummary(o.length() > 200 ? o.substring(0, 200) + "…" : o);
        }
        return vo;
    }

    private static String formatIso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static WorkflowInstanceStatus parseWorkflowInstanceStatusSafe(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        for (WorkflowInstanceStatus st : WorkflowInstanceStatus.values()) {
            if (st.name().equalsIgnoreCase(s) || st.getCode().equalsIgnoreCase(s)) {
                return st;
            }
        }
        return null;
    }

    private static TaskInstanceStatus parseTaskStatusSafe(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return TaskInstanceStatus.fromCode(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeTaskStatusParam(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
