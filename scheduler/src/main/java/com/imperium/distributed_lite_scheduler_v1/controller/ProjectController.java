package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListProjectsRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.PageResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.service.ProjectService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "项目", description = "项目空间 CRUD")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Operation(summary = "创建项目")
    @PostMapping("/create")
    public Result<Project> createProject(@RequestBody @Valid CreateProjectRequest request) {
        return projectService.createProject(request);
    }

    @Operation(summary = "分页查询项目列表")
    @GetMapping("/list")
    public Result<PageResponse<Project>> listProjects(@ModelAttribute @Valid ListProjectsRequest request) {
        return projectService.listProjects(request);
    }

    @Operation(summary = "查询项目详情")
    @GetMapping("/{projectId}")
    public Result<Project> getProject(
            @Parameter(description = "项目 ID") @PathVariable("projectId") Long projectId) {
        return projectService.getProject(projectId);
    }

    @Operation(summary = "更新项目")
    @PutMapping("/{projectId}")
    public Result<Project> updateProject(
            @PathVariable("projectId") Long projectId,
            @RequestBody @Valid UpdateProjectRequest request) {
        return projectService.updateProject(projectId, request);
    }

    @Operation(summary = "删除项目")
    @DeleteMapping("/{projectId}")
    public Result<Void> deleteProject(@PathVariable("projectId") Long projectId) {
        return projectService.deleteProject(projectId);
    }
}
