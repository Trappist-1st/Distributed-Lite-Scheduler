package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.constant.TaskInstanceStatus;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskInstanceMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchTaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.TaskSubmitResponse;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.security.JwtUserPrincipal;
import com.imperium.distributed_lite_scheduler_v1.service.TaskSubmitService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务提交服务实现（V2：同步事务写入，消除内存队列数据丢失风险）。
 *
 * <p>原设计使用 LinkedBlockingQueue 做削峰，存在两个可靠性问题：
 * 1. queue.offer() 返回成功但 JVM crash → insertBatch 未执行 → 调用方拿到 200 但任务丢失
 * 2. drainTo() 取出后 crash → 消息既不在队列也不在 DB 中
 *
 * <p>新设计：提交与持久化在同一个 @Transactional 边界内，调用方收到成功响应时任务
 * 已保证写入数据库，彻底消灭"已确认但未落库"的悬空窗口。
 * 批量场景通过 insertBatch 一次 SQL 完成，性能与原方案相当。
 */
@Slf4j
@Service
public class TaskSubmitServiceImpl implements TaskSubmitService {

    private static final int DEFAULT_PRIORITY = 5;
    private static final int MAX_BATCH_SIZE = 500;

    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TaskInstanceMapper taskInstanceMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<TaskSubmitResponse> submitTask(TaskSubmitRequest request) {
        try {
            Long tenantId;
            Long submitUserId;
            String traceId = MDC.get("traceId");

            if (request.getWorkflowInstanceId() != null) {
                if (request.getTenantId() == null) {
                    return Result.failure(400, "工作流任务提交缺少 tenantId");
                }
                tenantId = request.getTenantId();
                submitUserId = request.getSubmitUserId();
            } else {
                JwtUserPrincipal principal =
                        (JwtUserPrincipal)
                                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                tenantId = principal.tenantId();
                submitUserId = principal.userId();
                if (tenantId == null) {
                    return Result.failure(404, "租户信息不存在，请检查令牌");
                }
            }

            Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                    .eq(Task::getId, request.getTaskId())
                    .eq(Task::getStatus, 1));
            if (task == null) {
                return Result.failure(404, "任务不存在或已被禁用");
            }

            request.setTenantId(tenantId);
            request.setSubmitUserId(submitUserId);
            request.setTraceId(traceId);
            request.setTaskInstanceId(snowflake.nextId());
            request.setSubmitTime(LocalDateTime.now());

            Integer submittedPriority = request.getPriority();
            String submittedExecutorConfig = request.getExecutorConfig();
            String submittedResourceRequirement = request.getResourceRequirement();
            BeanUtils.copyProperties(task, request);

            if (org.springframework.util.StringUtils.hasText(submittedExecutorConfig)) {
                request.setExecutorConfig(submittedExecutorConfig);
            }
            if (org.springframework.util.StringUtils.hasText(submittedResourceRequirement)) {
                request.setResourceRequirement(submittedResourceRequirement);
            }
            if (submittedPriority != null) {
                request.setPriority(submittedPriority);
            } else if (request.getPriority() == null) {
                request.setPriority(DEFAULT_PRIORITY);
            }

            TaskInstance taskInstance = toTaskInstance(request);
            // 同步事务写入：调用方收到 200 时任务已持久化，不存在"已确认但未落库"的悬空窗口
            taskInstanceMapper.insert(taskInstance);

            log.info("任务提交成功 taskInstanceId={} tenantId={} taskId={} taskName={} traceId={}",
                    request.getTaskInstanceId(), tenantId, request.getTaskId(), task.getTaskName(), traceId);

            return Result.success(TaskSubmitResponse.builder()
                    .taskInstanceId(request.getTaskInstanceId())
                    .status(TaskInstanceStatus.PENDING.getCode())
                    .estimatedStartTime(Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("任务提交异常 taskId={}", request.getTaskId(), e);
            return Result.failure(500, "任务提交失败，请稍后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<BatchTaskSubmitResponse> submitBatch(BatchTaskSubmitRequest request) {
        List<TaskSubmitRequest> tasks = request.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return Result.failure(400, "批量提交的任务列表不能为空");
        }
        if (tasks.size() > MAX_BATCH_SIZE) {
            return Result.failure(400, "批量提交的任务数量不能超过 " + MAX_BATCH_SIZE);
        }

        List<Long> taskInstanceIds = new ArrayList<>();
        List<TaskInstance> toInsert = new ArrayList<>();
        int failedCount = 0;

        for (TaskSubmitRequest task : tasks) {
            Result<TaskSubmitResponse> result = prepareTaskInstance(task);
            if (result.isSuccess()) {
                taskInstanceIds.add(result.getData().getTaskInstanceId());
                // 从 request 中取出已构建好的 TaskInstance（通过 taskInstanceId 反查）
                toInsert.add(buildTaskInstanceFromRequest(task));
            } else {
                failedCount++;
                log.warn("批量提交任务预处理失败 taskId={} reason={}", task.getTaskId(), result.getMessage());
            }
        }

        if (!toInsert.isEmpty()) {
            taskInstanceMapper.insertBatch(toInsert);
        }

        log.info("批量提交统计 total={} success={} failed={}",
                tasks.size(), toInsert.size(), failedCount);

        if (toInsert.isEmpty()) {
            return Result.failure(500, "所有任务提交失败");
        }

        return Result.success(BatchTaskSubmitResponse.builder()
                .successCount(toInsert.size())
                .taskInstanceIds(taskInstanceIds)
                .build());
    }

    /**
     * 仅做校验和字段填充，不写库，返回已准备好的 TaskSubmitResponse。
     * 供 submitBatch 批量收集后一次性 insertBatch。
     */
    private Result<TaskSubmitResponse> prepareTaskInstance(TaskSubmitRequest request) {
        try {
            Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                    .eq(Task::getId, request.getTaskId())
                    .eq(Task::getStatus, 1));
            if (task == null) {
                return Result.failure(404, "任务不存在或已被禁用 taskId=" + request.getTaskId());
            }

            request.setTaskInstanceId(snowflake.nextId());
            request.setSubmitTime(LocalDateTime.now());

            Integer submittedPriority = request.getPriority();
            String submittedExecutorConfig = request.getExecutorConfig();
            String submittedResourceRequirement = request.getResourceRequirement();
            BeanUtils.copyProperties(task, request);

            if (org.springframework.util.StringUtils.hasText(submittedExecutorConfig)) {
                request.setExecutorConfig(submittedExecutorConfig);
            }
            if (org.springframework.util.StringUtils.hasText(submittedResourceRequirement)) {
                request.setResourceRequirement(submittedResourceRequirement);
            }
            if (submittedPriority != null) {
                request.setPriority(submittedPriority);
            } else if (request.getPriority() == null) {
                request.setPriority(DEFAULT_PRIORITY);
            }

            return Result.success(TaskSubmitResponse.builder()
                    .taskInstanceId(request.getTaskInstanceId())
                    .status(TaskInstanceStatus.PENDING.getCode())
                    .estimatedStartTime(Instant.now())
                    .build());
        } catch (Exception e) {
            return Result.failure(500, "任务预处理失败: " + e.getMessage());
        }
    }

    private TaskInstance buildTaskInstanceFromRequest(TaskSubmitRequest request) {
        TaskInstance taskInstance = new TaskInstance();
        BeanUtils.copyProperties(request, taskInstance,
                "taskInstanceId", "parameters", "traceId", "taskName", "taskType");
        taskInstance.setId(request.getTaskInstanceId());
        // 触发类型优先使用 request 中显式设置的值（RETRY / WORKFLOW / CRON），否则按来源推断
        if (org.springframework.util.StringUtils.hasText(request.getTriggerType())) {
            taskInstance.setTriggerType(request.getTriggerType());
        } else {
            taskInstance.setTriggerType(request.getWorkflowInstanceId() != null ? "WORKFLOW" : "API");
        }
        taskInstance.setStatus(TaskInstanceStatus.PENDING.getCode());
        taskInstance.setRetryCount(request.getRetryCount() != null ? request.getRetryCount() : 0);
        taskInstance.setVersion(0);
        taskInstance.setInstanceCode(String.valueOf(request.getTaskInstanceId()));
        taskInstance.setCreatedAt(LocalDateTime.now());
        taskInstance.setUpdatedAt(LocalDateTime.now());
        try {
            if (request.getParameters() != null && !request.getParameters().isEmpty()) {
                taskInstance.setParameters(objectMapper.writeValueAsString(request.getParameters()));
            }
        } catch (JsonProcessingException e) {
            log.error("参数序列化失败 taskInstanceId={}", request.getTaskInstanceId(), e);
            taskInstance.setParameters("{}");
        }
        return taskInstance;
    }

    private TaskInstance toTaskInstance(TaskSubmitRequest request) {
        TaskInstance taskInstance = buildTaskInstanceFromRequest(request);
        if (request.getWorkflowInstanceId() != null) {
            taskInstance.setWorkflowInstanceId(request.getWorkflowInstanceId());
        }
        return taskInstance;
    }
}
