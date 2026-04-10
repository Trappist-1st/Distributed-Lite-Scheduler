package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListProjectsRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.service.ProjectService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建项目
     * @param request
     * @return
     */
    @PostMapping("/create")
    public Result<Project> createProject(@RequestBody @Valid CreateProjectRequest request) {
        return projectService.createProject(request);
    }

    /**
     * 分页查询项目列表
     *
     */
    @GetMapping("/list")
    public Result<List<Project>> listProjects(@ModelAttribute @Valid ListProjectsRequest request) {
        return projectService.listProjects(request);
    }

    /**
     * 获取项目详情
     */
    @GetMapping("/{projectId}")
    public Result<Project> getProject(@PathVariable("projectId") Long projectId) {
        return projectService.getProject(projectId);
    }

    /**
     * 更新项目
     */
    @PutMapping("/{projectId}")
    public Result<Project> updateProject(@PathVariable("projectId") Long projectId, @RequestBody @Valid UpdateProjectRequest request) {
        return projectService.updateProject(projectId, request);
    }

    /**
     * 删除项目
     */
    @DeleteMapping("/{projectId}")
    public Result<Void> deleteProject(@PathVariable("projectId") Long projectId) {
        return projectService.deleteProject(projectId);
    }
}
