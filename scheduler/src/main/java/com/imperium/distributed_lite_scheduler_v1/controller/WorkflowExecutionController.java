package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.ParallelismReport;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowAnalysis;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowExecutionPlan;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowAnalysisService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutionService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "工作流分析", description = "执行计划预览、拓扑分析、并行度报告")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/workflow/execution")
@Slf4j
public class WorkflowExecutionController {
    
    @Autowired
    private WorkflowExecutionService executionService;
    
    @Autowired
    private WorkflowAnalysisService analysisService;
    
    @Operation(summary = "获取执行计划（预览）", description = "拓扑分层结果，含环检测")
    @GetMapping("/plan/{workflowId}")
    public Result<WorkflowExecutionPlan> getExecutionPlan(
            @Parameter(description = "工作流 ID") @PathVariable Long workflowId) {
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
    
    @Operation(summary = "工作流统计分析", description = "任务数、层数、最大并行度、加速比等")
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
    
    @Operation(summary = "并行度分析报告")
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
    
    @Operation(summary = "清除执行计划缓存", description = "工作流定义更新后调用")
    @DeleteMapping("/plan/cache/{workflowId}")
    public Result<Void> invalidateCache(@PathVariable Long workflowId) {
        log.info("清除执行计划缓存 workflowId={}", workflowId);
        executionService.invalidatePlanCache(workflowId);
        return Result.success();
    }
}
