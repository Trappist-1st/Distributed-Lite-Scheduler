package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.*;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutionService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.cache.DistributedWorkflowCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 工作流执行服务实现
 * 
 * 采用分布式两层缓存架构：
 * - L1 本地缓存（Caffeine）：单实例快速访问
 * - L2 分布式缓存（Redis）：跨实例共享与协调
 * 
 * 使用 DistributedWorkflowCacheManager 统一管理缓存生命周期，
 * 通过 Pub/Sub 机制实现跨实例的缓存失效通知。
 * 
 * @author system
 * @since 2024-01-01
 */
@Service
@Slf4j
public class WorkflowExecutionServiceImpl implements WorkflowExecutionService {
    
    @Autowired
    private WorkflowMapper workflowMapper;
    
    @Autowired
    private WorkflowService workflowService;
    
    @Autowired
    private DistributedWorkflowCacheManager cacheManager;
    
    @Override
    public WorkflowExecutionPlan buildExecutionPlan(Long workflowId) {
        // 使用分布式缓存管理器，实现 L1+L2 两层缓存协调
        // 若缓存未命中，doBuildExecutionPlan 作为 loader 从数据库加载
        return cacheManager.getExecutionPlan(workflowId, this::doBuildExecutionPlan);
    }
    
    /**
     * 实际构建执行计划的方法
     * 
     * 业务流程：
     * 1. 从数据库查询工作流定义
     * 2. 验证工作流存在性
     * 3. 解析DAG JSON
     * 4. 构建邻接表和任务映射表
     * 5. 执行拓扑排序
     * 6. 构建执行计划对象
     * 7. 记录日志
     * 
     * @param workflowId 工作流ID
     * @return 执行计划
     */
    private WorkflowExecutionPlan doBuildExecutionPlan(Long workflowId) {
        // 1. workflowMapper.selectById(workflowId)
        Workflow workflow = workflowMapper.selectById(workflowId);
        // 2. 检查workflow是否为null，为null抛出IllegalArgumentException
        if (workflow == null) {
            log.warn("工作流不存在 workflowId={}", workflowId);
            throw new IllegalArgumentException("工作流不存在");
        }
        String dagJson = workflow.getDagJson();
        if (dagJson == null || dagJson.isBlank()) {
            log.warn("工作流 DAG 为空 workflowId={}", workflowId);
            throw new IllegalArgumentException("工作流 DAG 定义不能为空");
        }
        // 3. 调用workflowService.parseDAG(workflow.getDagJson())解析DAG
        WorkflowDAG dag = workflowService.parseDAG(dagJson);
        // 4. 调用buildGraph(dag)构建邻接表
        Map<String, List<String>> graph = buildGraph(dag);
        // 5. 调用buildTaskMap(dag)构建任务映射
        Map<String, WorkflowTask> taskMap = buildTaskMap(dag);
        // 6. 调用topologicalSort(graph)得到分层结果
        List<List<String>> layers = topologicalSort(graph);
        // 7. 创建WorkflowExecutionPlan对象
        WorkflowExecutionPlan plan = new WorkflowExecutionPlan();
        // 8. 设置workflowId, workflowName, totalLayers
        plan.setWorkflowId(workflowId);
        plan.setWorkflowName(workflow.getWorkflowName());
        plan.setTotalLayers(layers.size());
        // 9. 遍历layers，构建TaskLayer列表：
        //    - 对每一层创建TaskLayer对象
        //    - 设置layerIndex
        //    - 遍历该层的任务名，创建TaskExecutionNode
        //    - 设置taskName, taskDefinition（从taskMap获取）, layerIndex
        //    - 将nodes设置到TaskLayer
        //    - 设置parallelism（nodes.size()）
        List<TaskLayer> taskLayers = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            List<String> taskNames = layers.get(i);
            TaskLayer layer = new TaskLayer();
            layer.setLayerIndex(i);
            List<TaskExecutionNode> nodes = new ArrayList<>();
            for (String taskName : taskNames) {
                WorkflowTask taskDef = taskMap.get(taskName);
                if (taskDef == null) {
                    log.error("任务定义不存在 taskName={} workflowId={}", taskName, workflowId);
                    throw new IllegalStateException("任务定义不存在: " + taskName);
                }
                TaskExecutionNode node = new TaskExecutionNode();
                node.setTaskName(taskName);
                node.setTaskDefinition(taskDef);
                node.setLayerIndex(i);
                node.setStatus(TaskNodeStatus.PENDING);
                nodes.add(node);
            }
            layer.setTasks(nodes);
            layer.setParallelism(nodes.size());
            taskLayers.add(layer);
        }

        // 10. 设置分层任务与各层并行度
        plan.setLayers(taskLayers);

        // 11. DAG 依赖边快照（含 P4-4 condition），随实例 execution_plan JSON 固化
        List<WorkflowDependency> depSnapshot =
                dag.getDependencies() != null
                        ? new ArrayList<>(dag.getDependencies())
                        : new ArrayList<>();
        plan.setDependencies(depSnapshot);

        // 12. 记录日志并返回
        log.info(
                "构建工作流执行计划成功 workflowId={} workflowName={} totalLayers={} totalTasks={}",
                workflowId,
                workflow.getWorkflowName(),
                plan.getTotalLayers(),
                plan.getTotalTaskCount());
        return plan;
    }

    // Kahn算法实现分层拓扑排序
    @Override
    public List<List<String>> topologicalSort(Map<String, List<String>> graph) {
        // 步骤1: 计算入度
        // - 创建 Map<String, Integer> inDegree
        Map<String, Integer> indegree = new HashMap<>();
        // 先初始化所有节点入度为0
        for(String node : graph.keySet()){
            indegree.putIfAbsent(node, 0);
        }
        // 再遍历边累加入度
        for(String node : graph.keySet()){
            for(String neighbor : graph.get(node)){
                indegree.put(neighbor, indegree.getOrDefault(neighbor, 0) + 1);
            }
        }

        // 步骤2: 初始化队列
        // - 创建 Queue<String> queue, ArrayDeque实现
        // - 遍历inDegree，将入度为0的节点加入队列
        Queue<String> queue = new ArrayDeque<>();
        for(String node : graph.keySet()){
            if(indegree.get(node) == 0){
                queue.offer(node);
            }
        }

        // 步骤3: 分层处理（关键）
        List<List<String>> layers = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while(!queue.isEmpty()){
            int layerSize = queue.size(); // 当前层的节点数
            List<String> currentLayer = new ArrayList<>();

            // 处理当前层的所有节点
            for(int i = 0; i < layerSize; i++){
                String node = queue.poll();
                currentLayer.add(node);
                visited.add(node);

                // 更新邻居的入度
                for(String neighbor : graph.getOrDefault(node, Collections.emptyList())){
                    indegree.put(neighbor, indegree.get(neighbor) - 1);

                    // 入度变为0，加入队列（下一层）
                    if(indegree.get(neighbor) == 0){
                        queue.offer(neighbor);
                    }
                }
            }
            layers.add(currentLayer);
        }

        // 步骤4: 检测环
        if(visited.size() != graph.size()){
            log.error("检测到环，已访问节点数={} 总节点数={}", visited.size(), graph.size());
            throw new IllegalStateException("DAG存在环，无法进行拓扑排序");
        }

        // 步骤5: 返回结果
        log.info("拓扑排序完成 layers={}", layers);
        return layers;
    }
    
    @Override
    public Map<String, List<String>> buildGraph(WorkflowDAG dag) {
        Map<String, List<String>> graph = new HashMap<>();
        for (WorkflowTask task : dag.getTasks()) {
            graph.put(task.getNodeName(), new ArrayList<>());
        }
        List<WorkflowDependency> deps =
                dag.getDependencies() != null ? dag.getDependencies() : Collections.emptyList();
        for (WorkflowDependency dep : deps) {
            List<String> outs = graph.get(dep.getFrom());
            if (outs == null) {
                throw new IllegalArgumentException("依赖的上游节点不存在: " + dep.getFrom());
            }
            outs.add(dep.getTo());
        }
        return graph;
    }
    
    @Override
    public Map<String, WorkflowTask> buildTaskMap(WorkflowDAG dag) {
        Map<String, WorkflowTask> taskMap = new HashMap<>();
        for(WorkflowTask task : dag.getTasks()){
            taskMap.put(task.getNodeName(), task);
        }
        return taskMap;
    }
    
    @Override
    public void invalidatePlanCache(Long workflowId) {
        // 委托给分布式缓存管理器
        // 它会：1. 清本实例 L1 缓存
        //      2. 清 Redis L2 缓存
        //      3. 发布 Pub/Sub 通知其他实例清除各自的 L1 缓存
        cacheManager.invalidateExecutionPlan(workflowId);
    }
    
    /**
     * 获取缓存统计信息（用于监控）
     * 返回本实例 L1 本地缓存的统计数据
     */
    public String getCacheStats() {
        return cacheManager.getCacheStats();
    }
}
