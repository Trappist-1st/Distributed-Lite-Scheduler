package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowCreateRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowUpdateRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowVO;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;

@Tag(name = "工作流定义", description = "DAG 工作流 CRUD")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/workflow")
@Slf4j
public class WorkflowController {
    
    @Autowired
    private WorkflowService workflowService;
    
    @Operation(summary = "创建工作流", description = "提交 DAG JSON 与元数据，返回工作流 ID")
    @PostMapping
    public Result<Long> createWorkflow(@RequestBody @Valid WorkflowCreateRequest request) {
        log.info("创建工作流 workflowName={}", request.getWorkflowName());
        try {
            Long workflowId = workflowService.createWorkflow(request);
            return Result.success(workflowId);
        } catch (IllegalArgumentException e) {
            log.warn("创建工作流失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("创建工作流状态异常: {}", e.getMessage());
            return Result.failure(ResultCode.INTERNAL_ERROR, e.getMessage());
        }
    }
    
    @Operation(summary = "查询工作流详情")
    @GetMapping("/{id}")
    public Result<WorkflowVO> getWorkflow(
            @Parameter(description = "工作流 ID") @PathVariable Long id) {
        log.info("查询工作流详情 id={}", id);
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流不存在");
        }
        return Result.success(workflowService.toVO(workflow));
    }
    
    @Operation(summary = "更新工作流")
    @PutMapping("/{id}")
    public Result<Void> updateWorkflow(
            @PathVariable Long id,
            @RequestBody @Valid WorkflowUpdateRequest request) {
        log.info("更新工作流 id={}", id);
        try {
            workflowService.updateWorkflow(id, request);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("更新工作流失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("更新工作流状态异常: {}", e.getMessage());
            return Result.failure(ResultCode.INTERNAL_ERROR, e.getMessage());
        }
    }
    
    @Operation(summary = "删除工作流", description = "逻辑删除")
    @DeleteMapping("/{id}")
    public Result<Void> deleteWorkflow(@PathVariable Long id) {
        log.info("删除工作流 id={}", id);
        try {
            workflowService.deleteWorkflow(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("删除工作流失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("删除工作流状态异常: {}", e.getMessage());
            return Result.failure(ResultCode.INTERNAL_ERROR, e.getMessage());
        }
    }
    
    @Operation(summary = "查询项目下工作流列表")
    @GetMapping("/list")
    public Result<List<WorkflowVO>> listWorkflows(
            @Parameter(description = "项目 ID") @RequestParam Long projectId,
            @Parameter(description = "状态：0-禁用，1-正常，空-全部") @RequestParam(required = false) Integer status) {
        log.info("查询工作流列表 projectId={} status={}", projectId, status);
        try {
            List<WorkflowVO> workflows = workflowService.listWorkflows(projectId, status);
            return Result.success(workflows);
        } catch (IllegalArgumentException e) {
            log.warn("查询工作流列表失败: {}", e.getMessage());
            return Result.failure(ResultCode.BAD_REQUEST, e.getMessage());
        }
    }
}
