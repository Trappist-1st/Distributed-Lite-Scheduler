package com.imperium.distributed_lite_scheduler_v1.service.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowDAG;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowTask;

import java.util.List;
import java.util.Map;

/**
 * 工作流执行服务接口
 * 
 * 负责工作流的拓扑排序和执行计划构建
 * 
 * @author system
 * @since 2024-01-01
 */
public interface WorkflowExecutionService {
    
    /**
     * 构建工作流执行计划
     * 
     * 核心业务流程：
     * 1. 从数据库获取工作流定义
     * 2. 解析DAG（调用WorkflowService.parseDAG）
     * 3. 构建邻接表表示（buildGraph）
     * 4. 执行分层拓扑排序（topologicalSort）
     * 5. 构建执行计划对象（WorkflowExecutionPlan）
     * 6. 返回执行计划
     * 
     * @param workflowId 工作流ID
     * @return 执行计划
     * @throws IllegalArgumentException 如果工作流不存在
     * @throws IllegalStateException 如果DAG存在环
     */
    WorkflowExecutionPlan buildExecutionPlan(Long workflowId);
    
    /**
     * 分层拓扑排序（Kahn算法）
     * 
     * 算法步骤：
     * 1. 计算所有节点的入度
     *    - 遍历邻接表，统计每个节点被指向的次数
     * 2. 将入度为0的节点加入队列（这些是起始节点）
     * 3. 分层处理（关键：同一轮处理一层）
     *    a. 记录当前队列大小 layerSize
     *    b. 创建当前层的任务列表 currentLayer
     *    c. 循环 layerSize 次：
     *       - 从队列取出节点
     *       - 加入currentLayer
     *       - 遍历该节点的所有邻居
     *       - 邻居入度减1
     *       - 如果邻居入度变为0，加入队列（下一层）
     *    d. 将 currentLayer 加入结果
     * 4. 检测环：如果访问的节点数 < 总节点数，说明存在环
     * 5. 返回分层结果 List<List<String>>
     * 
     * @param graph 邻接表表示的DAG，Key: 节点名, Value: 邻居列表
     * @return 分层结果，每层是一个任务名列表
     * @throws IllegalStateException 如果DAG存在环
     */
    List<List<String>> topologicalSort(Map<String, List<String>> graph);
    
    /**
     * 构建邻接表
     * 
     * 算法：
     * 1. 创建空的邻接表 Map<String, List<String>>
     * 2. 遍历DAG中的所有任务，初始化每个节点的邻居列表为空
     * 3. 遍历DAG中的所有依赖关系
     * 4. 对每个依赖 from -> to，在邻接表中添加边：graph.get(from).add(to)
     * 5. 返回邻接表
     * 
     * @param dag 工作流DAG
     * @return 邻接表
     */
    Map<String, List<String>> buildGraph(WorkflowDAG dag);
    
    /**
     * 构建任务映射表
     * 
     * 算法：
     * 1. 创建空的Map<String, WorkflowTask>
     * 2. 遍历DAG中的所有任务
     * 3. 以任务名为key，任务对象为value，放入Map
     * 4. 返回Map
     * 
     * @param dag 工作流DAG
     * @return 任务名到任务对象的映射
     */
    Map<String, WorkflowTask> buildTaskMap(WorkflowDAG dag);
    
    /**
     * 清除执行计划缓存
     * 
     * 当工作流定义更新时调用，确保下次获取最新的执行计划
     * 
     * @param workflowId 工作流ID
     */
    void invalidatePlanCache(Long workflowId);
}
