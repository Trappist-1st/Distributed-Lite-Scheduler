package com.imperium.distributed_lite_scheduler_v1.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowInstanceVO;
import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowControlService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowInstanceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工作流实例Controller
 *
 * <p>提供工作流实例的REST API</p>
 *
 * <p>API列表：</p>
 * <ul>
 *   <li>POST /api/workflow/instance/execute — 创建并执行工作流实例</li>
 *   <li>GET /api/workflow/instance/{id} — 查询工作流实例详情</li>
 *   <li>GET /api/workflow/instance/list — 查询工作流实例列表（分页）</li>
 *   <li>POST /api/workflow/instance/{id}/pause — 暂停工作流实例</li>
 *   <li>POST /api/workflow/instance/{id}/resume — 恢复工作流实例</li>
 *   <li>POST /api/workflow/instance/{id}/cancel — 取消工作流实例</li>
 *   <li>POST /api/workflow/instance/{id}/rerun — 重新运行工作流实例</li>
 *   <li>DELETE /api/workflow/instance/{id} — 删除工作流实例（仅终止态）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workflow/instance")
@Slf4j
public class WorkflowInstanceController {

    @Autowired
    private WorkflowInstanceService workflowInstanceService;

    @Autowired
    private WorkflowControlService workflowControlService;

    @Autowired
    private WorkflowInstanceMapper workflowInstanceMapper;

    @Autowired
    private WorkflowMapper workflowMapper;

    /**
     * 创建并执行工作流实例
     *
     * <p>POST /api/workflow/instance/execute</p>
     */
    @PostMapping("/execute")
    public Result<Map<String, Object>> executeWorkflow(@Valid @RequestBody WorkflowInstanceCreateRequest request) {
        log.info("接收到执行工作流请求, workflowId={}", request.getWorkflowId());
        try {
            Long instanceId = workflowInstanceService.createWorkflowInstance(request);
            WorkflowInstance instance = workflowInstanceService.getWorkflowInstance(instanceId);
            Map<String, Object> payload = new HashMap<>(4);
            payload.put("instanceId", instanceId);
            payload.put("status", instance != null ? instance.getStatus() : null);
            return Result.success(payload);
        } catch (IllegalArgumentException e) {
            log.warn("创建工作流实例失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("创建工作流实例状态异常: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("创建工作流实例异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    /**
     * 查询工作流实例详情
     *
     * <p>GET /api/workflow/instance/{id}</p>
     */
    @GetMapping("/{id}")
    public Result<WorkflowInstanceVO> getWorkflowInstance(@PathVariable Long id) {
        log.info("查询工作流实例详情, id={}", id);
        WorkflowInstance instance = workflowInstanceService.getWorkflowInstance(id);
        if (instance == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流实例不存在");
        }
        return Result.success(toWorkflowInstanceVO(instance));
    }

    /**
     * 查询工作流实例列表
     *
     * <p>GET /api/workflow/instance/list?workflowId=&amp;status=&amp;page=&amp;size=</p>
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> listWorkflowInstances(
            @RequestParam(required = false) Long workflowId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        log.info("查询工作流实例列表, workflowId={}, status={}", workflowId, status);
        Page<WorkflowInstance> pageParam = new Page<>(Math.max(1, page), Math.max(1, Math.min(100, size)));
        LambdaQueryWrapper<WorkflowInstance> qw = new LambdaQueryWrapper<>();
        qw.eq(workflowId != null, WorkflowInstance::getWorkflowId, workflowId);
        String normalizedStatus = normalizeWorkflowInstanceStatusParam(status);
        qw.eq(normalizedStatus != null, WorkflowInstance::getStatus, normalizedStatus);
        qw.orderByDesc(WorkflowInstance::getCreatedAt);
        IPage<WorkflowInstance> result = workflowInstanceMapper.selectPage(pageParam, qw);

        Map<String, Object> body = new HashMap<>(8);
        body.put("total", result.getTotal());
        body.put("page", result.getCurrent());
        body.put("size", result.getSize());
        body.put(
                "items",
                result.getRecords().stream().map(this::toWorkflowInstanceVO).toList());
        return Result.success(body);
    }

    /**
     * 暂停工作流实例
     *
     * <p>POST /api/workflow/instance/{id}/pause</p>
     */
    @PostMapping("/{id}/pause")
    public Result<Void> pauseWorkflow(@PathVariable Long id) {
        log.info("暂停工作流实例, id={}", id);
        try {
            workflowControlService.pauseWorkflowInstance(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("暂停工作流实例失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("暂停工作流实例状态不满足: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("暂停工作流实例异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    /**
     * 恢复工作流实例
     *
     * <p>POST /api/workflow/instance/{id}/resume</p>
     */
    @PostMapping("/{id}/resume")
    public Result<Void> resumeWorkflow(@PathVariable Long id) {
        log.info("恢复工作流实例, id={}", id);
        try {
            workflowControlService.resumeWorkflowInstance(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("恢复工作流实例失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("恢复工作流实例状态不满足: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("恢复工作流实例异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    /**
     * 取消工作流实例
     *
     * <p>POST /api/workflow/instance/{id}/cancel</p>
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancelWorkflow(@PathVariable Long id) {
        log.info("取消工作流实例, id={}", id);
        try {
            workflowControlService.cancelWorkflowInstance(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("取消工作流实例失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("取消工作流实例状态不满足: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("取消工作流实例异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    /**
     * 重新运行工作流实例
     *
     * <p>POST /api/workflow/instance/{id}/rerun</p>
     */
    @PostMapping("/{id}/rerun")
    public Result<Map<String, Object>> rerunWorkflow(@PathVariable Long id) {
        log.info("重新运行工作流实例, id={}", id);
        try {
            Long newId = workflowInstanceService.rerunWorkflowInstance(id);
            WorkflowInstance created = workflowInstanceService.getWorkflowInstance(newId);
            Map<String, Object> payload = new HashMap<>(4);
            payload.put("instanceId", newId);
            payload.put("status", created != null ? created.getStatus() : null);
            return Result.success(payload);
        } catch (IllegalArgumentException e) {
            log.warn("重新运行工作流实例失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("重新运行工作流实例状态异常: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("重新运行工作流实例异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    /**
     * 删除工作流实例（仅允许已终止实例）
     *
     * <p>DELETE /api/workflow/instance/{id}</p>
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteWorkflow(@PathVariable Long id) {
        log.info("删除工作流实例, id={}", id);
        try {
            workflowInstanceService.deleteWorkflowInstance(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("删除工作流实例失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("删除工作流实例状态不满足: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("删除工作流实例异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    /**
     * 重试失败的任务
     *
     * <p>POST /api/workflow/instance/{id}/retry-failed</p>
     */
    @PostMapping("/{id}/retry-failed")
    public Result<Map<String, Object>> retryFailedTasks(@PathVariable Long id) {
        log.info("重试失败的任务, id={}", id);
        try {
            int retryCount = workflowControlService.retryFailedTasks(id);
            return Result.success(Map.of("retryCount", retryCount));
        } catch (IllegalArgumentException e) {
            log.warn("重试失败任务失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("重试失败任务状态不满足: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("重试失败任务异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }

    private WorkflowInstanceVO toWorkflowInstanceVO(WorkflowInstance entity) {
        WorkflowInstanceVO vo = new WorkflowInstanceVO();
        vo.setId(entity.getId());
        vo.setWorkflowId(entity.getWorkflowId());
        vo.setInstanceCode(entity.getInstanceCode());
        vo.setTriggerType(entity.getTriggerType());
        vo.setTriggerUserId(entity.getTriggerUserId());
        vo.setStatus(entity.getStatus());
        WorkflowInstanceStatus st = parseWorkflowInstanceStatusSafe(entity.getStatus());
        vo.setStatusDescription(st != null ? st.getDescription() : null);
        vo.setTotalTasks(entity.getTotalTasks());
        vo.setSuccessTasks(entity.getSuccessTasks());
        vo.setFailedTasks(entity.getFailedTasks());
        vo.setProgress(computeOverallProgress(entity));
        vo.setStartTime(formatIso(entity.getStartTime()));
        vo.setEndTime(formatIso(entity.getEndTime()));
        vo.setDurationMs(entity.getDurationMs());
        vo.setCreatedAt(formatIso(entity.getCreatedAt()));
        vo.setUpdatedAt(formatIso(entity.getUpdatedAt()));

        if (entity.getWorkflowId() != null) {
            Workflow def = workflowMapper.selectById(entity.getWorkflowId());
            if (def != null) {
                vo.setWorkflowName(def.getWorkflowName());
            }
        }
        return vo;
    }

    private Double computeOverallProgress(WorkflowInstance entity) {
        Integer total = entity.getTotalTasks();
        if (total == null || total <= 0) {
            return 0.0;
        }
        int success = entity.getSuccessTasks() == null ? 0 : entity.getSuccessTasks();
        int failed = entity.getFailedTasks() == null ? 0 : entity.getFailedTasks();
        int done = success + failed;
        return Math.min(100.0, 100.0 * done / total);
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

    /**
     * 将查询参数中的状态（枚举名或持久化 code）规范为库中存值（与 {@link WorkflowInstanceStatus#getCode()} 一致）。
     */
    private static String normalizeWorkflowInstanceStatusParam(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        for (WorkflowInstanceStatus st : WorkflowInstanceStatus.values()) {
            if (st.name().equalsIgnoreCase(s) || st.getCode().equalsIgnoreCase(s)) {
                return st.getCode();
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
