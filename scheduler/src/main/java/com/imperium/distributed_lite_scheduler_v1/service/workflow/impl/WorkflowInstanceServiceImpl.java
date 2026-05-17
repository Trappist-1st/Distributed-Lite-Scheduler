package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.FailureStrategy;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.constant.TriggerType;
import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskExecutionNode;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.TaskLayer;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowTask;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutionService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutor;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowInstanceService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.security.WorkflowSecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 工作流实例服务实现类
 * 实现工作流实例的创建、查询、删除等功能
 */
@Service
@Slf4j
public class WorkflowInstanceServiceImpl implements WorkflowInstanceService {

    private static final int WORKFLOW_ENABLED = 1;
    private static final DateTimeFormatter INSTANCE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Autowired
    private WorkflowInstanceMapper workflowInstanceMapper;

    @Autowired
    private WorkflowTaskInstanceMapper workflowTaskInstanceMapper;

    @Autowired
    private WorkflowExecutionService workflowExecutionService;

    @Autowired
    private WorkflowExecutor workflowExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowService workflowService;

    /**
     * 创建工作流实例
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWorkflowInstance(WorkflowInstanceCreateRequest request) {
        log.info("开始创建工作流实例, workflowId={}", request.getWorkflowId());

        // 1. 验证工作流定义存在且状态正常（跨租户已由 WorkflowSecurityContextHolder 与工作流归属校验兜底）
        var workflowDefinition = workflowService.getById(request.getWorkflowId());
        if (workflowDefinition == null) {
            throw new IllegalArgumentException("工作流不存在或无访问权限");
        }
        if (workflowDefinition.getStatus() == null || workflowDefinition.getStatus() != WORKFLOW_ENABLED) {
            throw new IllegalStateException("工作流未启用或不可用");
        }

        // 2. 构建执行计划
        WorkflowExecutionPlan plan = workflowExecutionService.buildExecutionPlan(request.getWorkflowId());

        Long tenantId = WorkflowSecurityContextHolder.require().principal().tenantId();
        Long triggerUserId = resolveTriggerUserId(request.getTriggeredBy());
        FailureStrategy fs =
                request.getFailureStrategy() != null ? request.getFailureStrategy() : FailureStrategy.STOP_ON_FAILURE;

        WorkflowInstance entity = new WorkflowInstance();
        entity.setWorkflowId(request.getWorkflowId());
        entity.setTenantId(tenantId);
        entity.setTriggerType(
                request.getTriggerType() != null ? request.getTriggerType().name() : TriggerType.MANUAL.name());
        entity.setTriggerUserId(triggerUserId);
        entity.setFailureStrategy(fs.getCode());
        entity.setStatus(WorkflowInstanceStatus.PENDING.getCode());
        entity.setTotalTasks(plan.getTotalTaskCount());
        entity.setSuccessTasks(0);
        entity.setFailedTasks(0);

        String nameOrCode =
                StringUtils.hasText(request.getInstanceName())
                        ? request.getInstanceName().trim()
                        : generateInstanceName(workflowDefinition.getWorkflowName());
        entity.setInstanceCode(nameOrCode);

        try {
            entity.setExecutionPlan(objectMapper.writeValueAsString(plan));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化执行计划失败", e);
        }

        if (request.getWorkflowContext() != null && !request.getWorkflowContext().isEmpty()) {
            try {
                entity.setContextJson(objectMapper.writeValueAsString(request.getWorkflowContext()));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("实例上下文 workflowContext 序列化失败", e);
            }
        }

        if (workflowInstanceMapper.insert(entity) != 1 || entity.getId() == null) {
            throw new IllegalStateException("保存工作流实例失败");
        }

        try {
            createTaskSnapshots(entity.getId(), plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("持久化任务实例快照失败", e);
        }

        boolean runNow = request.getExecuteImmediately() == null || Boolean.TRUE.equals(request.getExecuteImmediately());
        if (runNow) {
            workflowExecutor.executeWorkflowInstanceAsync(entity.getId());
        }

        log.info("工作流实例创建成功, instanceId={}", entity.getId());
        return entity.getId();
    }

    /**
     * 根据ID获取工作流实例
     */
    @Override
    public WorkflowInstance getWorkflowInstance(Long instanceId) {
        log.info("查询工作流实例, instanceId={}", instanceId);

        WorkflowInstance workflowInstance = workflowInstanceMapper.selectById(instanceId);
        if (workflowInstance == null) {
            return null;
        }
        requireInstanceAccessible(workflowInstance);
        return workflowInstance;
    }

    /**
     * 重新运行工作流实例
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long rerunWorkflowInstance(Long instanceId) {
        log.info("重新运行工作流实例, instanceId={}", instanceId);

        WorkflowInstance previous = workflowInstanceMapper.selectById(instanceId);
        if (previous == null) {
            throw new IllegalArgumentException("工作流实例不存在: " + instanceId);
        }
        requireInstanceAccessible(previous);

        WorkflowInstanceCreateRequest next = new WorkflowInstanceCreateRequest();
        next.setWorkflowId(previous.getWorkflowId());
        next.setFailureStrategy(parseFailureStrategy(previous.getFailureStrategy()));
        next.setTriggerType(parseTriggerType(previous.getTriggerType()));
        next.setTriggeredBy(previous.getTriggerUserId());
        next.setExecuteImmediately(true);

        String baseName =
                previous.getInstanceCode() != null && previous.getInstanceCode().contains("_")
                        ? previous.getInstanceCode().substring(0, previous.getInstanceCode().lastIndexOf('_'))
                        : previous.getInstanceCode();
        next.setInstanceName(generateInstanceName(baseName != null ? baseName : "workflow"));

        return createWorkflowInstance(next);
    }

    /**
     * 删除工作流实例
     *
     * 注意：只能删除已终止的实例
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkflowInstance(Long instanceId) {
        log.info("删除工作流实例, instanceId={}", instanceId);

        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("工作流实例不存在: " + instanceId);
        }
        requireInstanceAccessible(instance);

        WorkflowInstanceStatus st = parseWorkflowInstanceStatus(instance.getStatus());
        if (!st.isTerminal()) {
            throw new IllegalStateException("仅允许删除已终止的工作流实例，当前状态: " + st.getCode());
        }

        workflowTaskInstanceMapper.delete(
                new LambdaQueryWrapper<WorkflowTaskInstance>()
                        .eq(WorkflowTaskInstance::getWorkflowInstanceId, instanceId));

        workflowInstanceMapper.deleteById(instanceId);
    }

    /**
     * 生成实例名称
     *
     * 格式：{工作流名称}_{yyyyMMdd_HHmmss}
     */
    @Override
    public String generateInstanceName(String workflowName) {
        String safe = workflowName != null ? workflowName.replaceAll("\\s+", "_").trim() : "workflow";
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return safe + "_" + INSTANCE_TS.format(LocalDateTime.now());
    }

    // ==================== 私有辅助方法 ====================

    private Long resolveTriggerUserId(Long triggeredByOverride) {
        if (triggeredByOverride != null) {
            return triggeredByOverride;
        }
        return WorkflowSecurityContextHolder.require().principal().userId();
    }

    /**
     * 创建任务实例
     *
     * 每条记录保存 {@link WorkflowTask} 快照，供执行引擎与调度器消费。
     */
    private void createTaskSnapshots(Long instanceId, WorkflowExecutionPlan plan) throws JsonProcessingException {
        if (plan == null || plan.getLayers() == null) {
            return;
        }
        for (TaskLayer layer : plan.getLayers()) {
            if (layer == null || layer.getTasks() == null) {
                continue;
            }
            Integer layerIx = layer.getLayerIndex();
            for (TaskExecutionNode node : layer.getTasks()) {
                if (node == null || !StringUtils.hasText(node.getTaskName())) {
                    continue;
                }
                WorkflowTaskInstance row = new WorkflowTaskInstance();
                row.setWorkflowInstanceId(instanceId);
                row.setTaskName(node.getTaskName().trim());
                WorkflowTask def = node.getTaskDefinition();
                row.setTaskDefinition(def == null ? "{}" : objectMapper.writeValueAsString(def));
                row.setStatus(TaskInstanceStatus.PENDING.getCode());
                row.setLayerIndex(layerIx != null ? layerIx : node.getLayerIndex());
                row.setRetryCount(0);

                workflowTaskInstanceMapper.insert(row);
            }
        }
    }

    private void requireInstanceAccessible(WorkflowInstance workflowInstance) {
        if (workflowInstance == null) {
            return;
        }
        if (workflowService.getById(workflowInstance.getWorkflowId()) == null) {
            throw new IllegalArgumentException("工作流实例不存在或无访问权限");
        }
    }

    private static WorkflowInstanceStatus parseWorkflowInstanceStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("工作流实例状态为空");
        }
        String s = raw.trim();
        for (WorkflowInstanceStatus st : WorkflowInstanceStatus.values()) {
            if (st.name().equalsIgnoreCase(s) || st.getCode().equalsIgnoreCase(s)) {
                return st;
            }
        }
        throw new IllegalArgumentException("不支持的工作流实例状态: " + raw);
    }

    private FailureStrategy parseFailureStrategy(String stored) {
        if (!StringUtils.hasText(stored)) {
            return FailureStrategy.STOP_ON_FAILURE;
        }
        try {
            return FailureStrategy.fromCode(stored.trim());
        } catch (Exception e) {
            return FailureStrategy.STOP_ON_FAILURE;
        }
    }

    private TriggerType parseTriggerType(String stored) {
        if (!StringUtils.hasText(stored)) {
            return TriggerType.MANUAL;
        }
        String token = stored.trim();
        for (TriggerType t : TriggerType.values()) {
            if (t.name().equalsIgnoreCase(token) || t.getCode().equalsIgnoreCase(token)) {
                return t;
            }
        }
        return TriggerType.MANUAL;
    }
}
