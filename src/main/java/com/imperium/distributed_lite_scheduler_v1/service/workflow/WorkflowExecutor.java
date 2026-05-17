package com.imperium.distributed_lite_scheduler_v1.service.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;

/**
 * 工作流执行引擎接口（DAG 编排 + 投递到调度队列）。
 *
 * <p>运行形态说明（已与早期「同进程 CountDownLatch 按层阻塞等待」草案不同）：</p>
 * <ul>
 *   <li>引擎只做编排与提交：<strong>PENDING</strong> 的 {@link com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance}
 *       经 {@link com.imperium.distributed_lite_scheduler_v1.service.TaskSubmitService} 进入调度侧；配额、选点、PENDING→RUNNING 均由调度器完成。</li>
 *   <li>任务完成后由 {@link com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionEventPublisher}
 *       投递到 Redis Stream，{@link com.imperium.distributed_lite_scheduler_v1.service.workflow.listener.TaskCompletionStreamListener}
 *       → {@link com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionStreamHandler}
 *       更新工作流任务状态并按层驱动下一批 Ready 节点的提交。</li>
 *   <li>并行度体现在「每层多任务同时处于调度队列 / RUNNING」，而非执行引擎线程在层内阻塞 join。</li>
 * </ul>
 */
public interface WorkflowExecutor {

    /**
     * 执行工作流实例（同步发起首轮投递）。
     *
     * <p>典型步骤：</p>
     * <ol>
     *   <li>校验实例状态（创建后应为 PENDING）</li>
     *   <li>置为 PREPARING 后校验/解析快照中的执行计划 JSON</li>
     *   <li>置为 RUNNING 并写入开始时间</li>
     *   <li>投递第 0 层仍为 PENDING 的节点（同层可能与后续 Stream 驱动的节点共存）</li>
     * </ol>
     * <p>后续层的推进依赖 Redis Stream 上的任务完成事件，不在此方法内阻塞等待整图结束。</p>
     *
     * @param instanceId 工作流实例ID
     */
    void executeWorkflowInstance(Long instanceId);

    /**
     * 异步执行工作流实例：提交到后台线程池后立刻返回，适合 HTTP 异步触发场景。
     *
     * @param instanceId 工作流实例ID
     */
    void executeWorkflowInstanceAsync(Long instanceId);

    /**
     * 异步继续投递仍为 PENDING 的节点。
     *
     * <p>用于暂停恢复、失败重试路径：调用方已将 {@link WorkflowInstance} 置回 RUNNING，由本方法在后台扫描最浅的含 PENDING 的层并再次提交。</p>
     *
     * @param instanceId 工作流实例ID
     */
    void resumeWorkflowInstanceAsync(Long instanceId);
}
