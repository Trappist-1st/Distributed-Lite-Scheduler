package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskStatusChangeLogMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.InternalTaskInstanceStatusTransitionRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskStatusChangeLog;
import com.imperium.distributed_lite_scheduler_v1.service.TaskInstanceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TaskInstanceServiceImpl extends ServiceImpl<TaskInstanceMapper, TaskInstance> implements TaskInstanceService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_TIMEOUT = "TIMEOUT";

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED, STATUS_TIMEOUT
    );

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            STATUS_PENDING, Set.of(STATUS_RUNNING, STATUS_CANCELLED),
            STATUS_RUNNING, Set.of(STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED, STATUS_TIMEOUT),
            STATUS_FAILED, Set.of(STATUS_PENDING),
            STATUS_TIMEOUT, Set.of(STATUS_PENDING),
            STATUS_SUCCESS, Set.of(),
            STATUS_CANCELLED, Set.of()
    );

    private final TaskMapper taskMapper;
    private final TaskStatusChangeLogMapper taskStatusChangeLogMapper;

    public TaskInstanceServiceImpl(TaskMapper taskMapper, TaskStatusChangeLogMapper taskStatusChangeLogMapper) {
        this.taskMapper = taskMapper;
        this.taskStatusChangeLogMapper = taskStatusChangeLogMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result<TaskInstance> transitionStatus(Long taskInstanceId, InternalTaskInstanceStatusTransitionRequest request) {
        String fromStatus = normalizeStatus(request.fromStatus());
        String toStatus = normalizeStatus(request.toStatus());

        if (!isKnownStatus(fromStatus) || !isKnownStatus(toStatus)) {
            return Result.failure(ResultCode.BAD_REQUEST, "状态值无效，仅支持 PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT");
        }
        if (!canTransition(fromStatus, toStatus)) {
            return Result.failure(ResultCode.BAD_REQUEST, "非法状态流转: " + fromStatus + " -> " + toStatus);
        }
        if (!StringUtils.hasText(request.triggerSource())) {
            return Result.failure(ResultCode.BAD_REQUEST, "triggerSource 不能为空");
        }

        TaskInstance current = baseMapper.selectById(taskInstanceId);
        if (current == null) {
            return Result.failure(ResultCode.NOT_FOUND, "任务实例不存在");
        }
        String currentStatus = normalizeStatus(current.getStatus());
        if (!fromStatus.equals(currentStatus)) {
            return Result.failure(ResultCode.CONFLICT, "当前状态不匹配，期望 " + fromStatus + "，实际 " + currentStatus);
        }

        //乐观锁更新状态机，保证并发安全；同时根据目标状态设置开始/结束时间。（我第一次实际使用乐观锁，确实是个不错的解决方案，避免了分布式锁的复杂性和性能问题）
        //首先获取时间戳，然后构造更新条件：id匹配、当前状态匹配、版本号匹配
        if (isRetryTransition(fromStatus, toStatus)) {
            int maxRetryTimes = resolveMaxRetryTimes(current.getTaskId());
            int currentRetry = current.getRetryCount() == null ? 0 : current.getRetryCount();
            if (currentRetry >= maxRetryTimes) {
                return Result.failure(ResultCode.BAD_REQUEST, "重试次数已达上限: " + maxRetryTimes);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<TaskInstance> uw = new LambdaUpdateWrapper<TaskInstance>()
                .eq(TaskInstance::getId, taskInstanceId)
                .eq(TaskInstance::getStatus, current.getStatus())
                .eq(TaskInstance::getVersion, current.getVersion())
                .set(TaskInstance::getStatus, toStatus);
        //如果任务转为运行态，就设置开始时间
        if (STATUS_RUNNING.equals(toStatus)) {
            uw.set(TaskInstance::getStartTime, now);
            uw.set(TaskInstance::getEndTime, null);
            uw.set(TaskInstance::getDurationMs, null);
            uw.set(TaskInstance::getExitCode, null);
            uw.set(TaskInstance::getErrorMessage, null);
        }
        //如果任务转为终止态，就设置结束时间
        if (TERMINAL_STATUSES.contains(toStatus)) {
            uw.set(TaskInstance::getEndTime, now);
            if (current.getStartTime() != null) {
                long durationMs = Math.max(0L, Duration.between(current.getStartTime(), now).toMillis());
                uw.set(TaskInstance::getDurationMs, durationMs);
            }
            if (STATUS_SUCCESS.equals(toStatus)) {
                uw.set(TaskInstance::getExitCode, request.exitCode() != null ? request.exitCode() : 0);
                uw.set(TaskInstance::getErrorMessage, null);
            } else if (STATUS_FAILED.equals(toStatus) || STATUS_TIMEOUT.equals(toStatus)) {
                uw.set(TaskInstance::getExitCode, request.exitCode());
                uw.set(TaskInstance::getErrorMessage,
                        StringUtils.hasText(request.errorMessage()) ? request.errorMessage().trim() : null);
            }
        }
        if (isRetryTransition(fromStatus, toStatus)) {
            int currentRetry = current.getRetryCount() == null ? 0 : current.getRetryCount();
            uw.set(TaskInstance::getRetryCount, currentRetry + 1);
            uw.set(TaskInstance::getScheduledTime, now);
            uw.set(TaskInstance::getResourceNodeId, null);
            uw.set(TaskInstance::getStartTime, null);
            uw.set(TaskInstance::getEndTime, null);
            uw.set(TaskInstance::getDurationMs, null);
            uw.set(TaskInstance::getExitCode, null);
            uw.set(TaskInstance::getErrorMessage, null);
        }

        //执行乐观锁更新，update(null, uw)：第一个参数为 null，表示完全由 Wrapper 指定更新内容
        //生成的 SQL 类似：
        /*UPDATE task_instance
        SET status = 'RUNNING',
            start_time = '2024-01-01 10:00:00',
            version = version + 1  -- MyBatis-Plus 自动处理版本号
        WHERE id = 123
          AND status = 'PENDING'
          AND version = 5*/
        int updated = baseMapper.update(null, uw);
        //检查更新行数，如果更新行数不是1行，说明版本冲突了。
        if (updated != 1) {
            return Result.failure(ResultCode.CONFLICT, "状态更新冲突，请重试");
        }

        //成功更新后返回最新状态的实体（重新查询）。
        persistStatusChangeLog(taskInstanceId, fromStatus, toStatus, request);

        TaskInstance latest = baseMapper.selectById(taskInstanceId);
        return Result.success(latest);
    }

    private int resolveMaxRetryTimes(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        Task task = taskMapper.selectById(taskId);
        if (task == null || task.getRetryTimes() == null || task.getRetryTimes() < 0) {
            return 0;
        }
        return task.getRetryTimes();
    }

    private static boolean isRetryTransition(String fromStatus, String toStatus) {
        return STATUS_PENDING.equals(toStatus)
                && (STATUS_FAILED.equals(fromStatus) || STATUS_TIMEOUT.equals(fromStatus));
    }

    private void persistStatusChangeLog(Long taskInstanceId,
                                        String fromStatus,
                                        String toStatus,
                                        InternalTaskInstanceStatusTransitionRequest request) {
        TaskStatusChangeLog log = new TaskStatusChangeLog();
        log.setTaskInstanceId(taskInstanceId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setTriggerSource(request.triggerSource().trim().toUpperCase(Locale.ROOT));
        log.setReason(StringUtils.hasText(request.reason()) ? request.reason().trim() : null);
        log.setOperatorUserId(request.operatorUserId());
        taskStatusChangeLogMapper.insert(log);
    }

    private static String normalizeStatus(String status) {
        return status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isKnownStatus(String status) {
        return ALLOWED_TRANSITIONS.containsKey(status);
    }

    private static boolean canTransition(String from, String to) {
        Set<String> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}

