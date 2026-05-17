package com.imperium.distributed_lite_scheduler_v1.service.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.*;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;

import java.util.List;

/**
 * 工作流服务接口
 * 
 * 提供工作流的CRUD、DAG解析、循环依赖检测等功能
 * 
 * @author UNSC
 * @since 2026-05-03
 */
public interface WorkflowService {
    
    /**
     * 创建工作流
     * 
     * 1. 解析DAG JSON
     * 2. 验证DAG合法性（循环依赖检测）
     * 3. 保存到数据库
     * 
     * @param request 创建请求
     * @return 工作流ID
     */
    Long createWorkflow(WorkflowCreateRequest request);
    
    /**
     * 更新工作流
     * 
     * 1. 验证工作流存在
     * 2. 解析并验证新的DAG
     * 3. 更新数据库（MyBatis-Plus会自动处理乐观锁version字段）
     * 
     * @param id 工作流ID
     * @param request 更新请求
     */
    void updateWorkflow(Long id, WorkflowUpdateRequest request);
    
    /**
     * 删除工作流（逻辑删除）
     * 
     * 注意：由于Workflow实体有@TableLogic注解，此操作是逻辑删除
     * 
     * @param id 工作流ID
     */
    void deleteWorkflow(Long id);
    
    /**
     * 根据ID查询工作流
     * 
     * @param id 工作流ID
     * @return 工作流实体
     */
    Workflow getById(Long id);
    
    /**
     * 转换为VO对象
     * 
     * @param workflow 工作流实体
     * @return 工作流VO
     */
    WorkflowVO toVO(Workflow workflow);
    
    /**
     * 查询项目下的工作流列表
     * 
     * @param projectId 项目ID
     * @param status 状态（可选）：0-禁用，1-正常，null-全部
     * @return 工作流列表
     */
    List<WorkflowVO> listWorkflows(Long projectId, Integer status);
    
    /**
     * 解析DAG JSON为对象
     * 
     * @param dagJson DAG JSON字符串
     * @return DAG对象
     * @throws IllegalArgumentException 如果JSON格式错误
     */
    WorkflowDAG parseDAG(String dagJson);
    
    /**
     * 验证DAG合法性
     * 
     * 1. 验证节点名称唯一性（nodeName不能重复）
     * 2. 验证引用的Task实体存在（taskId必须在task表中存在）
     * 3. 验证依赖引用的节点存在（from/to必须对应实际的nodeName）
     * 4. 检测循环依赖（DFS + 三色标记算法）
     * 
     * @param dag DAG对象
     * @throws IllegalArgumentException 如果DAG不合法
     */
    void validateDAG(WorkflowDAG dag);
}
