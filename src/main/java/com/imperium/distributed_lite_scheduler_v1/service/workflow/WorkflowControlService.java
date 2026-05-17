package com.imperium.distributed_lite_scheduler_v1.service.workflow;

/**
 * 工作流控制服务接口
 * 
 * 职责：
 * - 暂停工作流实例
 * - 恢复工作流实例
 * - 取消工作流实例
 * - 重试失败的任务
 */
public interface WorkflowControlService {
    
    /**
     * 暂停工作流实例
     * 
     * 约束：
     * - 只能暂停RUNNING状态的实例
     * 
     * 行为：
     * 1. 更新WorkflowInstance状态为PAUSED
     * 2. WorkflowExecutor检测到状态变化后停止调度新任务
     * 3. 已经在运行的任务继续执行完成
     * 
     * @param instanceId 工作流实例ID
     */
    void pauseWorkflowInstance(Long instanceId);
    
    /**
     * 恢复工作流实例
     * 
     * 约束：
     * - 只能恢复PAUSED状态的实例
     * 
     * 行为：
     * 1. 更新WorkflowInstance状态为RUNNING
     * 2. 重新提交到WorkflowExecutor执行队列
     * 3. 从暂停点继续执行（已完成的任务不重复执行）
     * 
     * @param instanceId 工作流实例ID
     */
    void resumeWorkflowInstance(Long instanceId);
    
    /**
     * 取消工作流实例
     * 
     * 约束：
     * - 不能取消已终止的实例（SUCCESS/FAILED/PARTIAL_SUCCESS/CANCELLED）
     * 
     * 行为：
     * 1. 更新WorkflowInstance状态为CANCELLED
     * 2. 取消所有RUNNING的WorkflowTaskInstance
     * 3. 调用TaskService取消对应的TaskInstance
     * 4. 将PENDING的WorkflowTaskInstance标记为SKIPPED
     * 
     * @param instanceId 工作流实例ID
     */
    void cancelWorkflowInstance(Long instanceId);
    
    /**
     * 重试失败的任务
     * 
     * 适用场景：
     * - 工作流实例状态为FAILED或PARTIAL_SUCCESS
     * - 有任务状态为FAILED
     * 
     * 行为：
     * 1. 找出所有FAILED状态的WorkflowTaskInstance
     * 2. 重置状态为PENDING
     * 3. 更新WorkflowInstance状态为RUNNING
     * 4. 重新提交到执行队列
     * 
     * @param instanceId 工作流实例ID
     * @return 重试的任务数量
     */
    int retryFailedTasks(Long instanceId);
    
    /**
     * 重试指定的任务
     * 
     * 重试单个任务，不影响其他任务
     * 
     * @param taskInstanceId 工作流任务实例ID
     */
    void retryTask(Long taskInstanceId);
    
    /**
     * 跳过失败的任务
     * 
     * 将失败的任务标记为SKIPPED，继续执行后续任务
     * 
     * @param instanceId 工作流实例ID
     * @return 跳过的任务数量
     */
    int skipFailedTasks(Long instanceId);
}
