package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.constant.WorkflowInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowTaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.service.executor.TaskExecutionCancelService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowControlService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutor;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.security.WorkflowSecurityContextHolder;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流控制服务实现类
 * 提供工作流实例的控制操作：暂停、恢复、取消、重试
 */
@Service
@Slf4j
public class WorkflowControlServiceImpl implements WorkflowControlService {

    @Autowired
    private WorkflowExecutor workflowExecutor;

    @Autowired
    private WorkflowInstanceMapper workflowInstanceMapper;

    @Autowired
    private WorkflowTaskInstanceMapper workflowTaskInstanceMapper;

    @Autowired
    private TaskInstanceService taskInstanceService;

    @Autowired
    private TaskExecutionCancelService taskExecutionCancelService;

    /**
     * 暂停工作流实例
     * 约束：
     * - 只能暂停RUNNING状态的实例
     * 行为：
     * 1. 更新WorkflowInstance状态为PAUSED
     * 2. WorkflowExecutor检测到状态变化后停止调度新任务
     * 3. 已经在运行的任务继续执行完成
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pauseWorkflowInstance(Long instanceId) {
        log.info("暂停工作流实例, instanceId={}", instanceId);

        WorkflowInstance instance = requireInstance(instanceId);
        WorkflowInstanceStatus st = parseWorkflowInstanceStatus(instance.getStatus());
        if (!st.canPause()) {
            throw new IllegalStateException("仅 RUNNING 状态可暂停，当前状态: " + st.getCode());
        }

        instance.setStatus(WorkflowInstanceStatus.PAUSED.getCode());
        workflowInstanceMapper.updateById(instance);
    }

    /**
     * 恢复工作流实例
     * 约束：
     * - 只能恢复PAUSED状态的实例
     * 行为：
     * 1. 更新WorkflowInstance状态为RUNNING
     * 2. 重新提交到WorkflowExecutor执行队列
     * 3. 从暂停点继续执行（已完成的任务不重复执行）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumeWorkflowInstance(Long instanceId) {
        log.info("恢复工作流实例, instanceId={}", instanceId);

        WorkflowInstance instance = requireInstance(instanceId);
        WorkflowInstanceStatus st = parseWorkflowInstanceStatus(instance.getStatus());
        if (!st.canResume()) {
            throw new IllegalStateException("仅 PAUSED 状态可恢复，当前状态: " + st.getCode());
        }

        instance.setStatus(WorkflowInstanceStatus.RUNNING.getCode());
        workflowInstanceMapper.updateById(instance);

        workflowExecutor.resumeWorkflowInstanceAsync(instanceId);
    }

    /**
     * 取消工作流实例
     * 约束：
     * - 不能取消已终止的实例（SUCCESS/FAILED/PARTIAL_SUCCESS/CANCELLED）
     * 行为：
     * 1. 更新WorkflowInstance状态为CANCELLED
     * 2. 取消所有RUNNING的WorkflowTaskInstance
     * 3. 调用TaskSubmitService取消对应的TaskInstance（经 {@link TaskInstanceService}）
     * 4. 将PENDING的WorkflowTaskInstance标记为SKIPPED
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelWorkflowInstance(Long instanceId) {
        log.info("取消工作流实例, instanceId={}", instanceId);

        WorkflowInstance instance = requireInstance(instanceId);
        WorkflowInstanceStatus st = parseWorkflowInstanceStatus(instance.getStatus());
        if (!st.canCancel()) {
            throw new IllegalStateException("该工作流实例不可取消（已终止或状态非法），当前状态: " + st.getCode());
        }

        Long operatorUserId = currentUserIdSafe();

        instance.setStatus(WorkflowInstanceStatus.CANCELLED.getCode());
        workflowInstanceMapper.updateById(instance);

        List<WorkflowTaskInstance> tasks = workflowTaskInstanceMapper.selectByInstanceId(instanceId);
        for (WorkflowTaskInstance wt : tasks) {
            TaskInstanceStatus t = safeTaskStatus(wt.getStatus());
            if (t == TaskInstanceStatus.RUNNING || t == TaskInstanceStatus.PENDING) {
                cancelSchedulerTaskIfPresent(wt, operatorUserId);
                wt.setStatus(TaskInstanceStatus.SKIPPED.getCode());
                workflowTaskInstanceMapper.updateById(wt);
            }
        }
    }

    /**
     * 重试失败的任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int retryFailedTasks(Long instanceId) {
        log.info("重试失败的任务, instanceId={}", instanceId);

        WorkflowInstance instance = requireInstance(instanceId);
        WorkflowInstanceStatus wf = parseWorkflowInstanceStatus(instance.getStatus());
        if (wf != WorkflowInstanceStatus.FAILED && wf != WorkflowInstanceStatus.PARTIAL_SUCCESS) {
            throw new IllegalStateException(
                    "仅 FAILED 或 PARTIAL_SUCCESS 状态实例可批量重试，当前状态: " + wf.getCode());
        }

        List<WorkflowTaskInstance> tasks = workflowTaskInstanceMapper.selectByInstanceId(instanceId);
        int retries = 0;
        List<WorkflowTaskInstance> resets = new ArrayList<>();
        for (WorkflowTaskInstance wt : tasks) {
            if (TaskInstanceStatus.FAILED.matches(wt.getStatus())) {
                resets.add(wt);
            }
        }
        for (WorkflowTaskInstance wt : resets) {
            resetWorkflowTaskForRetry(wt);
            retries++;
        }

        if (retries == 0) {
            return 0;
        }

        instance.setStatus(WorkflowInstanceStatus.RUNNING.getCode());
        instance.setEndTime(null);
        instance.setDurationMs(null);
        workflowInstanceMapper.updateById(instance);

        workflowExecutor.resumeWorkflowInstanceAsync(instanceId);
        return retries;
    }

    /**
     * 重试指定的任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryTask(Long workflowTaskInstanceId) {
        log.info("重试任务, workflowTaskInstanceId={}", workflowTaskInstanceId);

        WorkflowTaskInstance wt = workflowTaskInstanceMapper.selectById(workflowTaskInstanceId);
        if (wt == null) {
            throw new IllegalArgumentException("工作流任务实例不存在: " + workflowTaskInstanceId);
        }
        if (!TaskInstanceStatus.FAILED.matches(wt.getStatus())) {
            throw new IllegalStateException("仅 FAILED 的任务可重试");
        }

        resetWorkflowTaskForRetry(wt);

        WorkflowInstance parent = requireInstance(wt.getWorkflowInstanceId());
        parent.setStatus(WorkflowInstanceStatus.RUNNING.getCode());
        parent.setEndTime(null);
        parent.setDurationMs(null);
        workflowInstanceMapper.updateById(parent);

        workflowExecutor.resumeWorkflowInstanceAsync(parent.getId());
    }

    /**
     * 跳过失败的任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int skipFailedTasks(Long instanceId) {
        log.info("跳过失败的任务, instanceId={}", instanceId);

        WorkflowInstance instance = requireInstance(instanceId);

        List<WorkflowTaskInstance> tasks = workflowTaskInstanceMapper.selectByInstanceId(instanceId);
        int skipped = 0;
        for (WorkflowTaskInstance wt : tasks) {
            if (TaskInstanceStatus.FAILED.matches(wt.getStatus())) {
                wt.setStatus(TaskInstanceStatus.SKIPPED.getCode());
                workflowTaskInstanceMapper.updateById(wt);
                skipped++;
            }
        }

        if (skipped > 0) {
            int failed = instance.getFailedTasks() == null ? 0 : instance.getFailedTasks();
            instance.setFailedTasks(Math.max(0, failed - skipped));
            workflowInstanceMapper.updateById(instance);
        }

        return skipped;
    }

    // ==================== 私有辅助方法 ====================

    private WorkflowInstance requireInstance(Long instanceId) {
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("工作流实例不存在: " + instanceId);
        }
        return instance;
    }

    private Long currentUserIdSafe() {
        try {
            return WorkflowSecurityContextHolder.require().principal().userId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private void resetWorkflowTaskForRetry(WorkflowTaskInstance wt) {
        wt.setStatus(TaskInstanceStatus.PENDING.getCode());
        wt.setTaskInstanceId(null);
        wt.setStartTime(null);
        wt.setEndTime(null);
        wt.setDurationSeconds(null);
        wt.setExitCode(null);
        wt.setOutput(null);
        wt.setErrorMessage(null);
        int rc = wt.getRetryCount() == null ? 0 : wt.getRetryCount();
        wt.setRetryCount(rc + 1);
        workflowTaskInstanceMapper.updateById(wt);
    }

    private TaskInstanceStatus safeTaskStatus(String code) {
        try {
            return TaskInstanceStatus.fromCode(code);
        } catch (Exception e) {
            return TaskInstanceStatus.PENDING;
        }
    }

    /**
     * 取消单个任务对应的调度 TaskInstance。
     */
    private void cancelSchedulerTaskIfPresent(WorkflowTaskInstance taskInstance, Long operatorUserId) {
        Long sid = taskInstance.getTaskInstanceId();
        if (sid == null) {
            return;
        }
        TaskInstance ti = taskInstanceService.getById(sid);
        if (ti == null) {
            return;
        }
        TaskInstanceStatus cur;
        try {
            cur = TaskInstanceStatus.fromCode(ti.getStatus());
        } catch (Exception e) {
            return;
        }
        if (cur != TaskInstanceStatus.PENDING && cur != TaskInstanceStatus.RUNNING) {
            return;
        }
        if (cur == TaskInstanceStatus.RUNNING) {
            taskExecutionCancelService.cancelRunningTask(ti.getId(), ti.getResourceNodeId());
        }
        InternalTaskInstanceStatusTransitionRequest req =
                new InternalTaskInstanceStatusTransitionRequest(
                        cur.getCode(),
                        TaskInstanceStatus.CANCELLED.getCode(),
                        "SYSTEM",
                        "工作流实例取消",
                        operatorUserId,
                        null,
                        null);
        Result<TaskInstance> r = taskInstanceService.transitionStatus(ti.getId(), req);
        if (!r.isSuccess()) {
            log.warn(
                    "取消调度任务实例未成功 workflowTaskInstanceId={} schedulerTaskInstanceId={} msg={}",
                    taskInstance.getId(),
                    ti.getId(),
                    r.getMessage());
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
}
