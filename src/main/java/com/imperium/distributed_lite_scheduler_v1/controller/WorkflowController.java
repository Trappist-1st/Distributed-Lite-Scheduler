package com.imperium.distributed_lite_scheduler_v1.controller;

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

import jakarta.validation.Valid;
import java.util.List;

/**
 * 工作流Controller
 * 
 * 提供工作流的CRUD接口
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@RestController
@RequestMapping("/api/workflow")
@Slf4j
public class WorkflowController {
    
    @Autowired
    private WorkflowService workflowService;
    
    /**
     * 创建工作流
     * 
     * @param request 创建请求
     * @return 工作流ID
     */
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
    
    /**
     * 查询工作流详情
     * 
     * @param id 工作流ID
     * @return 工作流详情
     */
    @GetMapping("/{id}")
    public Result<WorkflowVO> getWorkflow(@PathVariable Long id) {
        log.info("查询工作流详情 id={}", id);
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.failure(ResultCode.NOT_FOUND, "工作流不存在");
        }
        return Result.success(workflowService.toVO(workflow));
    }
    
    /**
     * 更新工作流
     * 
     * @param id 工作流ID
     * @param request 更新请求
     * @return 操作结果
     */
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
    
    /**
     * 删除工作流（逻辑删除）
     * 
     * @param id 工作流ID
     * @return 操作结果
     */
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
    
    /**
     * 查询项目下的工作流列表
     * 
     * @param projectId 项目ID
     * @param status 状态（可选）：0-禁用，1-正常，null-全部
     * @return 工作流列表
     */
    @GetMapping("/list")
    public Result<List<WorkflowVO>> listWorkflows(
            @RequestParam Long projectId,
            @RequestParam(required = false) Integer status) {
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
