package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceRequirement;
import lombok.Data;

import java.util.List;

/**
 * 任务层
 * 
 * 表示拓扑排序后的一个执行层，该层内的所有任务可以并行执行
 * 
 * @author system
 * @since 2024-01-01
 */
@Data
public class TaskLayer {
    
    /**
     * 层级索引（从0开始）
     * 索引越小，执行优先级越高
     */
    private Integer layerIndex;
    
    /**
     * 该层的任务列表
     */
    private List<TaskExecutionNode> tasks;
    
    /**
     * 并行度（任务数量）
     */
    private Integer parallelism;
    
    /**
     * 计算该层的资源需求
     * 
     * 算法：
     * 1. 遍历该层所有任务
     * 2. 累加每个任务的CPU需求
     * 3. 累加每个任务的内存需求
     * 4. 返回总资源需求对象
     * 
     * @return 该层所需的总资源
     */
    public ResourceRequirement calculateResourceRequirement() {
        // 1. 创建ResourceRequirement对象
        // 2. 遍历tasks列表
        // 3. 对每个节点：WorkflowTask 上为「工作流级资源覆盖」resourceOverride；
        //    底层 Task 实体的默认资源不在本 DTO 中，若 override 为 null 则按 0 计入（调度前需解析 Task 再合并）
        // 4. 累加 cpu、memoryMb（与 ResourceRequirement 字段对齐）
        // 5. 处理null值（override 或字段为 null 时按 0）
        // 6. 返回结果
        ResourceRequirement total = new ResourceRequirement();
        double cpuSum = 0.0d;
        long memoryMbSum = 0L;
        if (tasks == null || tasks.isEmpty()) {
            total.setCpu(0.0d);
            total.setMemoryMb(0L);
            return total;
        }
        for (TaskExecutionNode node : tasks) {
            WorkflowTask def = node != null ? node.getTaskDefinition() : null;
            ResourceRequirement req = def != null ? def.getResourceOverride() : null;
            if (req == null) {
                continue;
            }
            if (req.getCpu() != null) {
                cpuSum += req.getCpu();
            }
            if (req.getMemoryMb() != null) {
                memoryMbSum += req.getMemoryMb();
            }
        }
        total.setCpu(cpuSum);
        total.setMemoryMb(memoryMbSum);
        return total;
    }
    
    /**
     * 判断该层所有任务是否已完成
     * 
     * @return true if all tasks completed
     */
    public boolean isAllTasksCompleted() {
        // 遍历tasks，检查所有任务的isCompleted()方法
        if (tasks == null || tasks.isEmpty()) {
            return true;
        }
        for (TaskExecutionNode node : tasks) {
            if (node == null || !node.isCompleted()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 获取该层已完成的任务数
     * 
     * @return 已完成任务数
     */
    public int getCompletedTaskCount() {
        // 统计tasks中status为SUCCESS或FAILED的数量
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (TaskExecutionNode node : tasks) {
            if (node == null) {
                continue;
            }
            TaskNodeStatus s = node.getStatus();
            if (s == TaskNodeStatus.SUCCESS || s == TaskNodeStatus.FAILED) {
                n++;
            }
        }
        return n;
    }
}
