package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.mapper.ProjectMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListProjectsRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateProjectRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;
import com.imperium.distributed_lite_scheduler_v1.service.ProjectService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {
    private static final Set<String> CREATE_PROJECT_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> UPDATE_PROJECT_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> DELETE_PROJECT_ROLES = Set.of("OWNER", "ADMIN");
    private static final int PROJECT_STATUS_ACTIVE = 1;
    private static final int NOT_DELETED = 0;
    private static final String MSG_PROJECT_NOT_FOUND = "项目不存在";
    private static final String MSG_PROJECT_CODE_EXISTS = "项目编码已存在";

    private final TenantAccessGuard tenantAccessGuard;


    public ProjectServiceImpl(TenantAccessGuard tenantAccessGuard) {
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * 创建项目（租户内权限校验版）。
     * <p>
     * 校验顺序：
     * 1) 必须已登录；2) 必须已切换租户；3) 必须是该租户成员且角色满足 OWNER/ADMIN/MEMBER；
     * 4) 同租户下 projectCode 不能重复。
     */
    @Override
    public Result<Project> createProject(CreateProjectRequest request) {
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                CREATE_PROJECT_ROLES, "当前角色无创建项目权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();
        Long userId = access.getData().principal().userId();

        String projectCode = request.projectCode().trim();
        // 约束项目编码在同租户内唯一，防止重复创建。
        Long duplicateCount = baseMapper.selectCount(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED)
                        .eq(Project::getProjectCode, projectCode));
        if (duplicateCount != null && duplicateCount > 0) {
            return Result.failure(ResultCode.CONFLICT, MSG_PROJECT_CODE_EXISTS);
        }

        Project project = new Project();
        project.setTenantId(tenantId);
        project.setCreatorUserId(userId);
        project.setProjectName(request.projectName().trim());
        project.setProjectCode(projectCode);
        project.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        project.setExtraConfig(StringUtils.hasText(request.extraConfig()) ? request.extraConfig().trim() : null);
        project.setStatus(request.status() != null ? request.status() : PROJECT_STATUS_ACTIVE);

        // 成功写入后返回实体（含数据库生成的主键）。
        int inserted = baseMapper.insert(project);
        if (inserted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "创建项目失败");
        }
        return Result.success(project);
    }

    @Override
    public Result<List<Project>> listProjects(ListProjectsRequest request) {
        // 列表查询要求：已登录 + 已切租户 + 至少是该租户成员（含 GUEST）。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(Set.of(), "");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        int page = request.page();
        int size = request.size();
        int offset = (page - 1) * size;
        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<Project>()
                .eq(Project::getTenantId, tenantId)
                .eq(Project::getDeleted, NOT_DELETED)
                .orderByDesc(Project::getCreatedAt)
                .last("LIMIT " + offset + ", " + size);
        if (StringUtils.hasText(request.keyword())) {
            String keyword = request.keyword().trim();
            qw.and(w -> w.like(Project::getProjectName, keyword)
                    .or()
                    .like(Project::getProjectCode, keyword));
        }
        List<Project> projects = baseMapper.selectList(qw);
        return Result.success(projects != null ? projects : new ArrayList<>());
    }

    @Override
    public Result<Project> getProject(Long projectId) {
        // 详情查询同样必须做租户边界过滤，避免通过 projectId 跨租户探测。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(Set.of(), "");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        Project project = baseMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_PROJECT_NOT_FOUND);
        }
        return Result.success(project);
    }

    @Override
    public Result<Project> updateProject(Long projectId, UpdateProjectRequest request) {
        // 更新项目要求租户成员角色为 OWNER/ADMIN/MEMBER。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                UPDATE_PROJECT_ROLES, "当前角色无更新项目权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        Project project = baseMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_PROJECT_NOT_FOUND);
        }

        if (StringUtils.hasText(request.projectCode())) {
            String newCode = request.projectCode().trim();
            Long duplicateCount = baseMapper.selectCount(
                    new LambdaQueryWrapper<Project>()
                            .eq(Project::getTenantId, tenantId)
                            .eq(Project::getDeleted, NOT_DELETED)
                            .eq(Project::getProjectCode, newCode)
                            .ne(Project::getId, projectId));
            if (duplicateCount != null && duplicateCount > 0) {
                return Result.failure(ResultCode.CONFLICT, MSG_PROJECT_CODE_EXISTS);
            }
            project.setProjectCode(newCode);
        }
        if (StringUtils.hasText(request.projectName())) {
            project.setProjectName(request.projectName().trim());
        }
        // 允许显式清空描述与扩展配置（传空串时转 null）。
        if (request.description() != null) {
            project.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        }
        if (request.extraConfig() != null) {
            project.setExtraConfig(StringUtils.hasText(request.extraConfig()) ? request.extraConfig().trim() : null);
        }
        if (request.status() != null) {
            project.setStatus(request.status());
        }

        int updated = baseMapper.updateById(project);
        if (updated != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "更新项目失败");
        }
        return Result.success(project);
    }

    @Override
    public Result<Void> deleteProject(Long projectId) {
        // 删除项目默认使用逻辑删除；仅 OWNER/ADMIN 可删。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                DELETE_PROJECT_ROLES, "当前角色无删除项目权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        Project project = baseMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_PROJECT_NOT_FOUND);
        }

        int deleted = baseMapper.deleteById(projectId);
        if (deleted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "删除项目失败");
        }
        return Result.success();
    }
}
