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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TaskSubmitServiceImpl implements TaskSubmitService {
    //在这里我们需要实现任务的削峰提交，即当提交的任务过多时，我们需要将它们放入一个队列中，按照一定的速率进行处理，以避免系统过载。
    //因此我们需要一个任务队列和一个定时器来定期从队列中取出任务进行处理。
    //我们可以使用Java的LinkedBlockingQueue来实现任务队列，使用ScheduledExecutorService来实现定时器。
    // 队列容量：10000
    // 提交侧削峰队列，避免高并发提交直接打满数据库。
    private static final int QUEUE_CAPACITY = 10000;

    // 入队最长等待时间（毫秒）
    private static final long OFFER_TIMEOUT_MS = 3000;
    // 每轮定时消费的最大条数
    private static final int BATCH_SIZE = 100;
    // 请求未指定优先级时使用的兜底值
    private static final int DEFAULT_PRIORITY = 5;

    private final BlockingQueue<TaskSubmitRequest> taskSubmitQueue;
    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TaskInstanceMapper taskInstanceMapper;
    
    @Autowired
    private TaskMapper taskMapper;

    public TaskSubmitServiceImpl() {
        this.taskSubmitQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    }

    @Override
    public Result<TaskSubmitResponse> submitTask(TaskSubmitRequest request) {
        try {
            Long taskId = request.getTaskId();

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
                    .eq(Task::getId, taskId)
                    .eq(Task::getStatus, 1));
            
            if (task == null) {
                return Result.failure(404, "任务不存在或已被禁用");
            }
            
            // 统一填充后端控制字段，避免客户端伪造关键元数据。
            request.setTenantId(tenantId);
            request.setSubmitUserId(submitUserId);
            request.setTraceId(traceId);
            request.setTaskInstanceId(snowflake.nextId());
            request.setSubmitTime(LocalDateTime.now());

            // 先拷贝任务定义快照字段，再恢复用户/工作流覆盖字段。
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

            // 优先级取值顺序：请求参数 > 任务定义 > 系统默认。
            if (submittedPriority != null) {
                request.setPriority(submittedPriority);
            } else if (request.getPriority() == null) {
                request.setPriority(DEFAULT_PRIORITY);
            }
            
            boolean isSubmitted = taskSubmitQueue.offer(request, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!isSubmitted) {
                return Result.failure(503, "任务提交队列已满，请稍后重试");
            }

            log.info("任务提交成功 taskInstanceId={} tenantId={} taskId={} taskName={} traceId={}",
                    request.getTaskInstanceId(), tenantId, taskId, task.getTaskName(), traceId);

            TaskSubmitResponse response = TaskSubmitResponse.builder()
                    .taskInstanceId(request.getTaskInstanceId())
                    .status(TaskInstanceStatus.PENDING.getCode())
                    .estimatedStartTime(Instant.now())
                    .build();
            return Result.success(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("任务提交被中断 taskId={}", request.getTaskId(), e);
            return Result.failure(500, "任务提交被中断");
        } catch (Exception e) {
            log.error("任务提交异常 taskId={}", request.getTaskId(), e);
            return Result.failure(500, "任务提交失败，请稍后重试");
        }
    }

    @Override
    public Result<BatchTaskSubmitResponse> submitBatch(BatchTaskSubmitRequest request) {
        List<TaskSubmitRequest> tasks = request.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return Result.failure(400, "批量提交的任务列表不能为空");
        }
        if (tasks.size() > 500) {
            return Result.failure(400, "批量提交的任务数量不能超过 500");
        }

        List<Long> taskInstanceIds = new ArrayList<>();
        int successCount = 0;

        // 逐条提交，允许部分成功，避免单条失败拖垮整批请求。
        for (TaskSubmitRequest task : tasks) {
            Result<TaskSubmitResponse> result = submitTask(task);
            if (result.isSuccess()) {
                TaskSubmitResponse response = result.getData();
                taskInstanceIds.add(response.getTaskInstanceId());
                successCount++;
            } else {
                log.warn("批量提交任务失败 taskId={} reason={}", task.getTaskId(), result.getMessage());
            }
        }

        BatchTaskSubmitResponse batchResponse = BatchTaskSubmitResponse.builder()
                .successCount(successCount)
                .taskInstanceIds(taskInstanceIds)
                .build();
        
        int totalCount = tasks.size();
        int failedCount = totalCount - successCount;

        log.info("批量提交统计 totalCount={} successCount={} failedCount={}", totalCount, successCount, failedCount);
        
        if (failedCount > 0) {
            log.warn("批量提交存在失败任务 successCount={} failedCount={} totalCount={}", successCount, failedCount, totalCount);
        }

        if (successCount > 0) {
            return Result.success(batchResponse);
        }

        return Result.failure(500, "所有任务提交失败");
    }

    @Scheduled(fixedDelay = 1000)
    public void batchConsume() {
        List<TaskSubmitRequest> batch = new ArrayList<>(BATCH_SIZE);
        // drainTo 减少锁竞争，适合固定节奏的批量消费场景。
        int drained = taskSubmitQueue.drainTo(batch, BATCH_SIZE);
        
        if (drained == 0) {
            return;
        }
        
        try {
            List<TaskInstance> taskInstances = new ArrayList<>(batch.size());
            for (TaskSubmitRequest request : batch) {
                taskInstances.add(toTaskInstance(request));
            }
            
            long startTime = System.currentTimeMillis();
            
            int affectedRows = taskInstanceMapper.insertBatch(taskInstances);
            if (affectedRows != taskInstances.size()) {
                log.warn("批量插入返回行数与请求数不一致 expected={} affected={}", taskInstances.size(), affectedRows);
            }
            
            long costTime = System.currentTimeMillis() - startTime;
            log.info("批量插入任务实例成功 count={} costMs={}", drained, costTime);
            
        } catch (Exception e) {
            log.error("批量插入任务实例失败 count={}", drained, e);
            handleBatchInsertFailure(batch, e);
        }
    }

    private TaskInstance toTaskInstance(TaskSubmitRequest request) {
        TaskInstance taskInstance = new TaskInstance();

        BeanUtils.copyProperties(request, taskInstance,
                "taskInstanceId", "parameters", "traceId", "taskName", "taskType");

        taskInstance.setId(request.getTaskInstanceId());
        if (request.getWorkflowInstanceId() != null) {
            taskInstance.setWorkflowInstanceId(request.getWorkflowInstanceId());
            taskInstance.setTriggerType("WORKFLOW");
        } else {
            taskInstance.setTriggerType("API");
        }
        taskInstance.setStatus(TaskInstanceStatus.PENDING.getCode());
        taskInstance.setRetryCount(0);
        taskInstance.setVersion(0);
        
        try {
            if (request.getParameters() != null && !request.getParameters().isEmpty()) {
                taskInstance.setParameters(objectMapper.writeValueAsString(request.getParameters()));
            }
        } catch (JsonProcessingException e) {
            // 参数解析异常不阻塞主流程，写入空 JSON 并保留错误日志。
            log.error("参数序列化失败 taskInstanceId={}", request.getTaskInstanceId(), e);
            taskInstance.setParameters("{}");
        }

        return taskInstance;
    }

    /**
     * 批量入库失败处理
     */
    private void handleBatchInsertFailure(List<TaskSubmitRequest> batch, Exception e) {
        // 1) 记录失败日志（批次大小、异常信息、关键trace）
        // 2) 对批次中的每条请求进行失败策略处理：
        //    - 优先重试：重入提交队列（可限制最大重试次数）
        //    - 重试耗尽：写入死信队列/失败表，等待人工或补偿任务处理
        // 3) 记录失败指标与告警（失败数、重试数、死信数）
        // 当前策略：尽力重入队；重入失败由日志暴露，后续可接死信队列。
        log.error("批量入库失败，尝试重新入队 batchSize={}", batch.size(), e);
        
        int requeueCount = 0;
        for (TaskSubmitRequest request : batch) {
            try {
                boolean requeued = taskSubmitQueue.offer(request, 100, TimeUnit.MILLISECONDS);
                if (requeued) {
                    requeueCount++;
                } else {
                    log.error("任务重新入队失败 taskInstanceId={} taskId={}", 
                            request.getTaskInstanceId(), request.getTaskId());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("任务重新入队被中断 taskInstanceId={}", request.getTaskInstanceId(), ie);
                break;
            }
        }
        
        log.warn("批量入库失败处理完成 totalCount={} requeueCount={} lostCount={}", 
                batch.size(), requeueCount, batch.size() - requeueCount);
    }
}
