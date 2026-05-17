package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.ParallelismReport;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowAnalysis;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowAnalysisService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutionService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 工作流执行控制器
 * 
 * 提供工作流执行计划相关的API
 * 
 * @author system
 * @since 2024-01-01
 */
@RestController
@RequestMapping("/api/workflow/execution")
@Slf4j
public class WorkflowExecutionController {
    
    @Autowired
    private WorkflowExecutionService executionService;
    
    @Autowired
    private WorkflowAnalysisService analysisService;
    
    /**
     * 获取工作流执行计划（预览）
     * 
     * 业务流程：
     * 1. 接收workflowId
     * 2. 记录日志
     * 3. 调用executionService.buildExecutionPlan(workflowId)
     * 4. 返回执行计划
     * 
     * 异常处理：
     * - IllegalArgumentException: 工作流不存在 → 400
     * - IllegalStateException: DAG存在环 → 400
     * - Exception: 其他错误 → 500
     * 
     * @param workflowId 工作流ID
     * @return 执行计划
     */
    @GetMapping("/plan/{workflowId}")
    public Result<WorkflowExecutionPlan> getExecutionPlan(@PathVariable Long workflowId) {
        log.info("获取执行计划 workflowId={}", workflowId);
        try {
            WorkflowExecutionPlan plan = executionService.buildExecutionPlan(workflowId);
            return Result.success(plan);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("获取执行计划失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("获取执行计划异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }
    
    /**
     * 分析工作流（统计信息）
     * 
     * 业务流程：
     * 1. 构建执行计划
     * 2. 创建WorkflowAnalysis对象
     * 3. 设置统计信息：
     *    - totalTasks: 总任务数
     *    - totalLayers: 总层数
     *    - maxParallelism: 最大并行度
     *    - minExecutionMinutes: 理论最短时间（假设每任务1分钟）
     *    - serialExecutionMinutes: 串行执行时间
     *    - speedup: 加速比
     * 4. 返回分析结果
     * 
     * @param workflowId 工作流ID
     * @return 工作流分析结果
     */
    @GetMapping("/analysis/{workflowId}")
    public Result<WorkflowAnalysis> analyzeWorkflow(@PathVariable Long workflowId) {
        log.info("分析工作流 workflowId={}", workflowId);
        try {
            WorkflowExecutionPlan plan = executionService.buildExecutionPlan(workflowId);
            WorkflowAnalysis analysis = new WorkflowAnalysis();
            int totalTasks = plan.getTotalTaskCount();
            Integer totalLayers = plan.getTotalLayers();
            int layersCount = totalLayers != null ? totalLayers : 0;
            analysis.setTotalTasks(totalTasks);
            analysis.setTotalLayers(totalLayers);
            analysis.setMaxParallelism(plan.getMaxParallelism());
            analysis.setMinExecutionMinutes(layersCount);
            analysis.setSerialExecutionMinutes(totalTasks);
            double speedup = layersCount == 0 ? 0.0 : (double) totalTasks / layersCount;
            analysis.setSpeedup(speedup);
            return Result.success(analysis);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("分析工作流失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("分析工作流异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }
    
    /**
     * 获取并行度分析报告
     * 
     * 业务流程：
     * 1. 构建执行计划
     * 2. 调用analysisService.analyzeParallelism(plan)
     * 3. 返回详细的并行度报告
     * 
     * @param workflowId 工作流ID
     * @return 并行度报告
     */
    @GetMapping("/parallelism-report/{workflowId}")
    public Result<ParallelismReport> getParallelismReport(@PathVariable Long workflowId) {
        log.info("获取并行度报告 workflowId={}", workflowId);
        try {
            WorkflowExecutionPlan plan = executionService.buildExecutionPlan(workflowId);
            ParallelismReport report = analysisService.analyzeParallelism(plan);
            return Result.success(report);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("获取并行度报告失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("获取并行度报告异常", e);
            return Result.failure(ResultCode.INTERNAL_ERROR, "服务器内部错误");
        }
    }
    
    /**
     * 清除执行计划缓存
     * 
     * 用途：当工作流定义更新后，清除缓存以获取最新的执行计划
     * 
     * @param workflowId 工作流ID
     * @return 操作结果
     */
    @DeleteMapping("/plan/cache/{workflowId}")
    public Result<Void> invalidateCache(@PathVariable Long workflowId) {
        log.info("清除执行计划缓存 workflowId={}", workflowId);
        executionService.invalidatePlanCache(workflowId);
        return Result.success();
    }
}
