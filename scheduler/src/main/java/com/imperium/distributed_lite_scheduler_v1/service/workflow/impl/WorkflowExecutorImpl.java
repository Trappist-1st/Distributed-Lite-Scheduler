package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 工作流执行引擎实现类 - DAG引擎
 * 
 * ⭐⭐⭐ 核心职责：只负责工作流逻辑编排，不做资源管理
 * 
 * DAG引擎的职责：
 * 1. 解析DAG拓扑
 * 2. 计算就绪任务（入度为0或上游完成）
 * 3. 创建TaskInstance(status=PENDING)
 * 4. 提交就绪任务给调度器（通过TaskSubmitService）
 * 5. 监听任务完成事件（通过EventListener）
 * 6. 计算下一批就绪任务
 * 7. 标记工作流完成或失败
 * 
 * ❌ DAG引擎不负责：
 * - 配额检查（调度器职责）
 * - 节点选择（调度器职责）
 * - 资源预留（调度器职责）
 * - 状态流转 PENDING→RUNNING（调度器职责）
 * - 提交执行器（调度器职责）
 * 
 * ✅ 正确的流程：
 * DAG引擎创建PENDING任务 → 提交给TaskSubmitService → 
 * 任务进入削峰队列 → 调度器从队列取出 → 
 * 调度器执行资源管理决策 → 执行器执行 → 
 * 任务完成事件 → DAG引擎监听并处理 → 计算下一批任务
 */
@Service
@Slf4j
public class WorkflowExecutorImpl implements WorkflowExecutor {
    
    @Autowired
    private WorkflowInstanceMapper workflowInstanceMapper;
    
    @Autowired
    private WorkflowTaskInstanceMapper workflowTaskInstanceMapper;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowLayerDispatchFacade workflowLayerDispatchFacade;
    
    @Autowired
    @Qualifier("workflowExecutorThreadPool")
    private ExecutorService executorService;
    
    /**
     * 执行工作流实例
     * 
     * 注意：此方法只负责启动工作流执行，实际的任务执行由以下组件协同完成：
     * 1. DAG引擎（本类）：编排任务顺序，提交PENDING任务
     * 2. 调度器：资源管理决策，状态流转
     * 3. 执行器：实际执行任务
     * 4. 事件监听器：监听任务完成，触发DAG引擎继续编排
     */
    @Override
    public void executeWorkflowInstance(Long instanceId) {
        log.info("开始执行工作流实例, instanceId={}", instanceId);
        
        // 1. 验证实例状态（必须是PENDING）
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("工作流实例不存在: " + instanceId);
        }

        if (parseWorkflowInstanceStatus(instance.getStatus()) != WorkflowInstanceStatus.PENDING) {
            throw new IllegalStateException("工作流实例状态不正确，当前状态: " + instance.getStatus());
        }

        // 2. 更新状态为PREPARING
        updateInstanceStatus(instanceId, WorkflowInstanceStatus.PREPARING);

        try {
            // 3. 解析执行计划 JSON（校验可读）
            parseExecutionPlan(instance.getExecutionPlan());

            // 4. 更新状态为RUNNING，记录开始时间
            instance.setStatus(WorkflowInstanceStatus.RUNNING.getCode());
            instance.setStartTime(LocalDateTime.now());
            workflowInstanceMapper.updateById(instance);

            // 5. 提交第0层（入度为0的任务）到调度器
            Long submitUserId =
                    instance.getTriggerUserId() != null ? instance.getTriggerUserId() : instance.getTenantId();
            workflowLayerDispatchFacade.dispatchLayer(instanceId, 0, submitUserId);

            log.info("工作流实例启动完成, instanceId={}, 已提交第0层任务到调度器", instanceId);

            // 注意：不在这里等待任务完成
            // 任务完成后，TaskCompletionStreamListener 消费 Stream 事件并触发下一批任务的提交

        } catch (Exception e) {
            log.error("工作流实例执行失败 instanceId={}", instanceId, e);
            failWorkflowInstance(instance, e.getMessage());
            throw new RuntimeException("工作流执行失败", e);
        }
    }
    
    /**
     * 异步执行工作流实例
     */
    @Override
    public void executeWorkflowInstanceAsync(Long instanceId) {
        log.info("异步执行工作流实例, instanceId={}", instanceId);
        
        executorService.submit(() -> {
            try {
                executeWorkflowInstance(instanceId);
            } catch (Exception e) {
                log.error("异步执行工作流实例失败 instanceId={}", instanceId, e);
            }
        });
    }

    @Override
    public void resumeWorkflowInstanceAsync(Long instanceId) {
        log.info("异步继续投递Pending工作流任务, instanceId={}", instanceId);
        executorService.submit(() -> {
            try {
                continueSchedulePendingLayers(instanceId);
            } catch (Exception e) {
                log.error("继续投递Pending工作流任务失败 instanceId={}", instanceId, e);
            }
        });
    }

    /**
     * 从仍存在 PENDING 节点的最浅层重新提交到调度队列（与同层 RUNNING 任务共存）。
     */
    private void continueSchedulePendingLayers(Long instanceId) throws Exception {
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("工作流实例不存在: " + instanceId);
        }
        WorkflowInstanceStatus wfStatus = parseWorkflowInstanceStatus(instance.getStatus());
        if (wfStatus != WorkflowInstanceStatus.RUNNING) {
            throw new IllegalStateException(
                    "工作流实例必须为 RUNNING 后方可继续投递，当前状态: " + wfStatus.getCode());
        }
        Integer minLayer = computeMinPendingLayer(instanceId);
        if (minLayer != null) {
            Long submitUserId =
                    instance.getTriggerUserId() != null ? instance.getTriggerUserId() : instance.getTenantId();
            workflowLayerDispatchFacade.dispatchLayer(instanceId, minLayer, submitUserId);
            log.info("已继续投递第{}层 PENDING 任务 instanceId={}", minLayer, instanceId);
        } else {
            log.info("未发现 PENDING 工作流节点，跳过投递 instanceId={}", instanceId);
        }
    }

    private Integer computeMinPendingLayer(Long instanceId) {
        List<WorkflowTaskInstance> all = workflowTaskInstanceMapper.selectByInstanceId(instanceId);
        int min = Integer.MAX_VALUE;
        boolean found = false;
        for (WorkflowTaskInstance t : all) {
            if (TaskInstanceStatus.PENDING.matches(t.getStatus())) {
                found = true;
                if (t.getLayerIndex() != null) {
                    min = Math.min(min, t.getLayerIndex());
                }
            }
        }
        return found ? min : null;
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
        throw new IllegalArgumentException("Unsupported workflow instance status: " + raw);
    }

    // ==================== 私有辅助方法 ====================

    private void failWorkflowInstance(WorkflowInstance instance, String errorMessage) {
        instance.setStatus(WorkflowInstanceStatus.FAILED.getCode());
        instance.setEndTime(LocalDateTime.now());

        if (instance.getStartTime() != null) {
            long ms = java.time.Duration.between(
                    instance.getStartTime(),
                    instance.getEndTime()
            ).toMillis();
            instance.setDurationMs(ms);
        }

        workflowInstanceMapper.updateById(instance);

        log.error("工作流实例执行失败 instanceId={} error={}", instance.getId(), errorMessage);
    }

    /**
     * 解析执行计划 JSON
     */
    private WorkflowExecutionPlan parseExecutionPlan(String json) throws Exception {
        return objectMapper.readValue(json, WorkflowExecutionPlan.class);
    }

    /**
     * 更新实例状态
     */
    private void updateInstanceStatus(Long instanceId, WorkflowInstanceStatus status) {
        WorkflowInstance patch = new WorkflowInstance();
        patch.setId(instanceId);
        patch.setStatus(status.getCode());
        workflowInstanceMapper.updateById(patch);
    }
}
