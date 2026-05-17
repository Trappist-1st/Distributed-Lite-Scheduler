package com.imperium.distributed_lite_scheduler_v1.service.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;

/**
 * 工作流实例服务接口
 * 
 * 职责：
 * - 创建工作流实例
 * - 初始化任务实例
 * - 构建执行计划快照
 * - 管理实例生命周期
 */
public interface WorkflowInstanceService {
    
    /**
     * 创建工作流实例
     * 
     * 步骤：
     * 1. 验证工作流定义存在且状态正常
     * 2. 构建执行计划（调用WorkflowExecutionService）
     * 3. 创建WorkflowInstance记录
     * 4. 创建WorkflowTaskInstance记录（批量）
     * 5. 如果executeImmediately=true，提交到执行队列
     * 
     * @param request 创建请求
     * @return 工作流实例ID
     */
    Long createWorkflowInstance(WorkflowInstanceCreateRequest request);
    
    /**
     * 根据ID获取工作流实例
     * 
     * @param instanceId 实例ID
     * @return 工作流实例
     */
    WorkflowInstance getWorkflowInstance(Long instanceId);
    
    /**
     * 重新运行工作流实例
     * 
     * 基于已有实例创建新的实例，复用原有配置
     * 
     * @param instanceId 原实例ID
     * @return 新实例ID
     */
    Long rerunWorkflowInstance(Long instanceId);
    
    /**
     * 删除工作流实例
     * 
     * 注意：只能删除已终止的实例
     * 
     * @param instanceId 实例ID
     */
    void deleteWorkflowInstance(Long instanceId);
    
    /**
     * 生成实例名称
     * 
     * 格式：{工作流名称}_{yyyyMMdd_HHmmss}
     * 
     * @param workflowName 工作流名称
     * @return 实例名称
     */
    String generateInstanceName(String workflowName);
}
