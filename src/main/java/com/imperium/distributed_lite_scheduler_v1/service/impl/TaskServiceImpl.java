package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.mapper.ProjectMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.BatchCreateTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListTasksRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.UpdateTaskRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;
import com.imperium.distributed_lite_scheduler_v1.service.TaskService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {
    private static final int TASK_STATUS_ACTIVE = 1;
    private static final int NOT_DELETED = 0;
    private static final Set<String> CREATE_TASK_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> LIST_TASKS_ROLES = Set.of("OWNER", "ADMIN", "MEMBER", "GUEST");
    private static final Set<String> UPDATE_TASK_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> DELETE_TASK_ROLES = Set.of("OWNER", "ADMIN");
    private static final String MSG_PROJECT_NOT_FOUND = "项目不存在";
    private static final String MSG_TASK_NOT_FOUND = "任务不存在";
    private static final String MSG_TASK_CODE_EXISTS = "任务编码已存在";

    private final ProjectMapper projectMapper;
    private final TenantAccessGuard tenantAccessGuard;

    public TaskServiceImpl(ProjectMapper projectMapper, TenantAccessGuard tenantAccessGuard) {
        this.projectMapper = projectMapper;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    @Override
    public Result<Task> createTask(CreateTaskRequest request) {
        //首先检验用户是否登录，然后检验用户是否在这个tenant里
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                CREATE_TASK_ROLES, "当前角色无创建任务权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();
        Long userId = access.getData().principal().userId();

        //根据请求中的projectId把项目查出来
        // 这里额外带 tenantId 过滤，防止仅凭 projectId 跨租户创建任务。
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, request.projectId())
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_PROJECT_NOT_FOUND);
        }

        //如果上述检查都通过了，就新建Task对象，设置/拷贝属性，然后保存到数据库中
        Task task = new Task();
        task.setProjectId(project.getId());
        task.setTaskName(request.taskName().trim());

        String taskCode = StringUtils.hasText(request.taskCode()) ? request.taskCode().trim() : null;
        if (taskCode != null) {
            Long duplicateCount = baseMapper.selectCount(
                    new LambdaQueryWrapper<Task>()
                            .eq(Task::getProjectId, project.getId())
                            .eq(Task::getDeleted, NOT_DELETED)
                            .eq(Task::getTaskCode, taskCode));
            if (duplicateCount != null && duplicateCount > 0) {
                return Result.failure(ResultCode.CONFLICT, MSG_TASK_CODE_EXISTS);
            }
        }
        task.setTaskCode(taskCode);

        task.setTaskType(request.taskType().trim());
        task.setExecutorConfig(StringUtils.hasText(request.executorConfig()) ? request.executorConfig().trim() : null);
        task.setScheduleType(StringUtils.hasText(request.scheduleType()) ? request.scheduleType().trim() : "MANUAL");
        task.setCronExpression(StringUtils.hasText(request.cronExpression()) ? request.cronExpression().trim() : null);
        task.setTimeoutSeconds(request.timeoutSeconds() != null ? request.timeoutSeconds() : 3600);
        task.setRetryTimes(request.retryTimes() != null ? request.retryTimes() : 0);
        task.setRetryInterval(request.retryInterval() != null ? request.retryInterval() : 0);
        task.setPriority(request.priority() != null ? request.priority() : 5);
        task.setResourceRequire(StringUtils.hasText(request.resourceRequire()) ? request.resourceRequire().trim() : null);
        task.setAlertOnFailure(request.alertOnFailure() != null ? request.alertOnFailure() : 0);
        task.setAlertOnTimeout(request.alertOnTimeout() != null ? request.alertOnTimeout() : 0);
        task.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        task.setCreatorUserId(userId);
        task.setStatus(request.status() != null ? request.status() : TASK_STATUS_ACTIVE);

        int inserted = baseMapper.insert(task);
        if (inserted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "创建任务失败");
        }

        //返回包装在Result对象中的Task对象
        return Result.success(task);
    }

    @Override
    public Result<List<Task>> listTasks(ListTasksRequest request) {

        //首先检验用户是否登录，然后检验用户是否在这个tenant里
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                LIST_TASKS_ROLES, "当前角色无查看任务列表权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        //只是列举所有的人物列表，这个并不需要太高的权限，租户内的GUEST也可以查看

        //根据请求中的projectId把项目查出来，确认项目存在且用户有权限访问
        Long projectId = request.projectId();
        if (projectId != null) {
            Project project = projectMapper.selectOne(
                    new LambdaQueryWrapper<Project>()
                            .eq(Project::getId, projectId)
                            .eq(Project::getTenantId, tenantId)
                            .eq(Project::getDeleted, NOT_DELETED));
            if (project == null) {
                return Result.failure(ResultCode.NOT_FOUND, MSG_PROJECT_NOT_FOUND);
            }
        }

        //把项目中的任务列表查出来，返回包装在Result对象中的List<Task>对象
        int page = request.page();
        int size = request.size();
        int offset = (page - 1) * size;

        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<>();
        if (projectId != null) {
            // 传入 projectId 时，直接按 project_id 查询（上面已完成 tenant 边界校验）。
            qw.eq(Task::getProjectId, projectId);
        } else {
            // 未传 projectId 时，通过可见项目集合收敛任务范围，避免跨租户数据泄漏。
            List<Project> tenantProjects = projectMapper.selectList(
                    new LambdaQueryWrapper<Project>()
                            .eq(Project::getTenantId, tenantId)
                            .eq(Project::getDeleted, NOT_DELETED));
            if (tenantProjects.isEmpty()) {
                return Result.success(List.of());
            }
            List<Long> tenantProjectIds = tenantProjects.stream().map(Project::getId).toList();
            qw.in(Task::getProjectId, tenantProjectIds);
        }
        qw.eq(Task::getDeleted, NOT_DELETED);
        if (request.status() != null) {
            qw.eq(Task::getStatus, request.status());
        }
        if (StringUtils.hasText(request.keyword())) {
            String keyword = request.keyword().trim();
            qw.and(w -> w.like(Task::getTaskName, keyword).or().like(Task::getTaskCode, keyword));
        }
        qw.orderByDesc(Task::getCreatedAt).last("LIMIT " + offset + ", " + size);

        List<Task> tasks = baseMapper.selectList(qw);
        return Result.success(tasks);
    }

    @Override
    public Result<Task> getTask(Long taskId) {
        // 获取任务详情：登录态 + 租户上下文 + 成员权限 + 资源归属校验（task -> project -> tenant）。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                LIST_TASKS_ROLES, "当前角色无查看任务详情权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        Task task = baseMapper.selectOne(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getId, taskId)
                        .eq(Task::getDeleted, NOT_DELETED));
        if (task == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_TASK_NOT_FOUND);
        }
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, task.getProjectId())
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            // 不暴露跨租户任务是否存在，统一返回不存在。
            return Result.failure(ResultCode.NOT_FOUND, MSG_TASK_NOT_FOUND);
        }
        return Result.success(task);
    }

    @Override
    public Result<Task> updateTask(Long taskId, UpdateTaskRequest request) {
        // 更新任务：登录态 + 租户上下文 + 成员角色（OWNER/ADMIN/MEMBER）+ 资源归属校验。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                UPDATE_TASK_ROLES, "当前角色无更新任务权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        Task task = baseMapper.selectOne(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getId, taskId)
                        .eq(Task::getDeleted, NOT_DELETED));
        if (task == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_TASK_NOT_FOUND);
        }
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, task.getProjectId())
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_TASK_NOT_FOUND);
        }

        if (StringUtils.hasText(request.taskCode())) {
            String newCode = request.taskCode().trim();
            Long duplicateCount = baseMapper.selectCount(
                    new LambdaQueryWrapper<Task>()
                            .eq(Task::getProjectId, task.getProjectId())
                            .eq(Task::getDeleted, NOT_DELETED)
                            .eq(Task::getTaskCode, newCode)
                            .ne(Task::getId, taskId));
            if (duplicateCount != null && duplicateCount > 0) {
                return Result.failure(ResultCode.CONFLICT, MSG_TASK_CODE_EXISTS);
            }
            task.setTaskCode(newCode);
        }
        if (StringUtils.hasText(request.taskName())) {
            task.setTaskName(request.taskName().trim());
        }
        if (StringUtils.hasText(request.taskType())) {
            task.setTaskType(request.taskType().trim());
        }
        if (request.executorConfig() != null) {
            task.setExecutorConfig(StringUtils.hasText(request.executorConfig()) ? request.executorConfig().trim() : null);
        }
        if (request.scheduleType() != null) {
            task.setScheduleType(StringUtils.hasText(request.scheduleType()) ? request.scheduleType().trim() : null);
        }
        if (request.cronExpression() != null) {
            task.setCronExpression(StringUtils.hasText(request.cronExpression()) ? request.cronExpression().trim() : null);
        }
        if (request.timeoutSeconds() != null) {
            task.setTimeoutSeconds(request.timeoutSeconds());
        }
        if (request.retryTimes() != null) {
            task.setRetryTimes(request.retryTimes());
        }
        if (request.retryInterval() != null) {
            task.setRetryInterval(request.retryInterval());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.resourceRequire() != null) {
            task.setResourceRequire(StringUtils.hasText(request.resourceRequire()) ? request.resourceRequire().trim() : null);
        }
        if (request.alertOnFailure() != null) {
            task.setAlertOnFailure(request.alertOnFailure());
        }
        if (request.alertOnTimeout() != null) {
            task.setAlertOnTimeout(request.alertOnTimeout());
        }
        if (request.description() != null) {
            task.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        }
        if (request.status() != null) {
            task.setStatus(request.status());
        }

        int updated = baseMapper.updateById(task);
        if (updated != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "更新任务失败");
        }
        return Result.success(task);
    }

    @Override
    public Result<Void> deleteTask(Long taskId) {
        // 删除任务：默认逻辑删除，仅 OWNER/ADMIN 可执行。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                DELETE_TASK_ROLES, "当前角色无删除任务权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();

        Task task = baseMapper.selectOne(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getId, taskId)
                        .eq(Task::getDeleted, NOT_DELETED));
        if (task == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_TASK_NOT_FOUND);
        }
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, task.getProjectId())
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_TASK_NOT_FOUND);
        }

        int deleted = baseMapper.deleteById(taskId);
        if (deleted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "删除任务失败");
        }
        return Result.success();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result<List<Task>> batchCreateTasks(BatchCreateTasksRequest request) {
        // 批量创建策略：同一 project、同一 tenant；任一失败整体回滚，避免部分成功导致脏数据。
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(
                CREATE_TASK_ROLES, "当前角色无批量创建任务权限");
        if (!access.isSuccess()) {
            return Result.failure(access.getCode(), access.getMessage());
        }
        Long tenantId = access.getData().principal().tenantId();
        Long userId = access.getData().principal().userId();

        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, request.projectId())
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
        if (project == null) {
            return Result.failure(ResultCode.NOT_FOUND, MSG_PROJECT_NOT_FOUND);
        }

        List<BatchCreateTasksRequest.BatchTaskItem> items = request.tasks();
        if (items == null || items.isEmpty()) {
            return Result.failure(ResultCode.BAD_REQUEST, "任务列表不能为空");
        }

        Set<String> codesInBatch = new HashSet<>();
        // 先做完整校验，避免边插入边失败导致部分成功。
        for (BatchCreateTasksRequest.BatchTaskItem item : items) {
            String taskCode = StringUtils.hasText(item.taskCode()) ? item.taskCode().trim() : null;
            if (taskCode == null) {
                continue;
            }
            String key = taskCode.toUpperCase(Locale.ROOT);
            if (!codesInBatch.add(key)) {
                return Result.failure(ResultCode.CONFLICT, "批量任务中存在重复任务编码: " + taskCode);
            }
            Long duplicateCount = baseMapper.selectCount(
                    new LambdaQueryWrapper<Task>()
                            .eq(Task::getProjectId, project.getId())
                            .eq(Task::getDeleted, NOT_DELETED)
                            .eq(Task::getTaskCode, taskCode));
            if (duplicateCount != null && duplicateCount > 0) {
                return Result.failure(ResultCode.CONFLICT, MSG_TASK_CODE_EXISTS + ": " + taskCode);
            }
        }

        List<Task> created = new ArrayList<>(items.size());
        for (BatchCreateTasksRequest.BatchTaskItem item : items) {
            String taskCode = StringUtils.hasText(item.taskCode()) ? item.taskCode().trim() : null;

            Task task = new Task();
            task.setProjectId(project.getId());
            task.setTaskName(item.taskName().trim());
            task.setTaskCode(taskCode);
            task.setTaskType(item.taskType().trim());
            task.setExecutorConfig(StringUtils.hasText(item.executorConfig()) ? item.executorConfig().trim() : null);
            task.setScheduleType(StringUtils.hasText(item.scheduleType()) ? item.scheduleType().trim() : "MANUAL");
            task.setCronExpression(StringUtils.hasText(item.cronExpression()) ? item.cronExpression().trim() : null);
            task.setTimeoutSeconds(item.timeoutSeconds() != null ? item.timeoutSeconds() : 3600);
            task.setRetryTimes(item.retryTimes() != null ? item.retryTimes() : 0);
            task.setRetryInterval(item.retryInterval() != null ? item.retryInterval() : 0);
            task.setPriority(item.priority() != null ? item.priority() : 5);
            task.setResourceRequire(StringUtils.hasText(item.resourceRequire()) ? item.resourceRequire().trim() : null);
            task.setAlertOnFailure(item.alertOnFailure() != null ? item.alertOnFailure() : 0);
            task.setAlertOnTimeout(item.alertOnTimeout() != null ? item.alertOnTimeout() : 0);
            task.setDescription(StringUtils.hasText(item.description()) ? item.description().trim() : null);
            task.setCreatorUserId(userId);
            task.setStatus(item.status() != null ? item.status() : TASK_STATUS_ACTIVE);

            int inserted = baseMapper.insert(task);
            if (inserted != 1) {
                return Result.failure(ResultCode.INTERNAL_ERROR, "批量创建任务失败");
            }
            created.add(task);
        }
        return Result.success(created);
    }

}
