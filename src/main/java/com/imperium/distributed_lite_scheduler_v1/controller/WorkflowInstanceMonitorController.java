package com.imperium.distributed_lite_scheduler_v1.controller;

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

/**
 * 工作流实例监控Controller
 *
 * <p>提供工作流实例监控相关的API（进度、任务列表、执行计划快照、时间线）。</p>
 *
 * <p>说明：任务完成后的下一层推进依赖 Redis Stream 消费（见 {@link com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionStreamHandler}），
 * 本 Controller 仅基于当前库表状态做只读聚合。</p>
 *
 * <p>API列表：</p>
 * <ul>
 *   <li>GET /api/workflow/instance/{id}/progress — 获取实时进度</li>
 *   <li>GET /api/workflow/instance/{id}/tasks — 获取任务列表</li>
 *   <li>GET /api/workflow/instance/{id}/tasks/{taskId} — 获取任务详情</li>
 *   <li>GET /api/workflow/instance/{id}/execution-plan — 获取执行计划快照（JSON 反序列化）</li>
 *   <li>GET /api/workflow/instance/{id}/timeline — 获取执行时间线（甘特图数据）</li>
 * </ul>
 */
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

    /**
     * 获取工作流实例实时进度
     *
     * <p>GET /api/workflow/instance/{id}/progress</p>
     */
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

    /**
     * 获取工作流实例的任务列表
     *
     * <p>GET /api/workflow/instance/{id}/tasks</p>
     */
    @GetMapping("/{id}/tasks")
    public Result<List<WorkflowTaskInstanceVO>> getTasks(
            @PathVariable Long id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer layerIndex) {
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

    /**
     * 获取工作流任务实例详情
     *
     * <p>GET /api/workflow/instance/{id}/tasks/{taskId}</p>
     */
    @GetMapping("/{id}/tasks/{taskId}")
    public Result<WorkflowTaskInstanceVO> getTaskDetail(@PathVariable Long id, @PathVariable Long taskId) {
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

    /**
     * 获取执行计划（实例创建时写入的快照）
     *
     * <p>GET /api/workflow/instance/{id}/execution-plan</p>
     */
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

    /**
     * 获取执行时间线（用于甘特图等可视化）
     *
     * <p>GET /api/workflow/instance/{id}/timeline</p>
     */
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
