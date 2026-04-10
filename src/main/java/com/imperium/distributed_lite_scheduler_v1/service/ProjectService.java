package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListProjectsRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

import java.util.List;

public interface ProjectService {
    Result<Project> createProject(CreateProjectRequest request);

    Result<List<Project>> listProjects(ListProjectsRequest request);

    Result<Project> getProject(Long projectId);

    Result<Project> updateProject(Long projectId, UpdateProjectRequest request);

    Result<Void> deleteProject(Long projectId);
}
