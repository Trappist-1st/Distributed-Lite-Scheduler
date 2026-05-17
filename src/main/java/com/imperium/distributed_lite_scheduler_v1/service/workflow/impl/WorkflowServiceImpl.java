package com.imperium.distributed_lite_scheduler_v1.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.ProjectMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TaskMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.WorkflowMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.*;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Project;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Task;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowExecutionService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.WorkflowService;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.annotation.RequireWorkflowPermission;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.condition.WorkflowConditionSyntaxValidator;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.security.WorkflowSecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工作流服务实现
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Service
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {

    private static final int NOT_DELETED = 0;

    @Autowired
    private WorkflowMapper workflowMapper;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private ProjectMapper projectMapper;

    /**
     * 执行计划缓存失效（与 {@link WorkflowExecutionService} 解耦；{@code @Lazy} 避免与解析 DAG 的循环依赖）。
     */
    @Autowired
    @Lazy
    private WorkflowExecutionService workflowExecutionService;

    @Autowired
    private WorkflowConditionSyntaxValidator workflowConditionSyntaxValidator;

    /**
     * 验证项目是否属于指定租户
     * @return 如果项目存在且属于租户则返回Project对象，否则返回null
     */
    private Project validateProjectBelongsToTenant(Long projectId, Long tenantId) {
        return projectMapper.selectOne(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, projectId)
                        .eq(Project::getTenantId, tenantId)
                        .eq(Project::getDeleted, NOT_DELETED));
    }

    // ==================== 辅助方法：更新操作的字段处理 =====================
    /**
     * 验证并更新工作流编码（需要检查重复性）
     */
    private void updateWorkflowCode(Workflow workflow, String newCode) {
        if (newCode == null) {
            return;  // 请求中没有工作流编码更新
        }
        if (!StringUtils.hasText(newCode)) {
            throw new IllegalArgumentException("工作流编码不能为空");
        }
        String code = newCode.trim();
        if (!code.equals(workflow.getWorkflowCode())) {
            Long dup = workflowMapper.selectCount(
                    new LambdaQueryWrapper<Workflow>()
                            .eq(Workflow::getProjectId, workflow.getProjectId())
                            .eq(Workflow::getWorkflowCode, code)
                            .ne(Workflow::getId, workflow.getId()));
            if (dup != null && dup > 0) {
                throw new IllegalArgumentException("工作流编码已存在");
            }
            workflow.setWorkflowCode(code);
        }
    }

    /**
     * 验证并更新DAG定义
     */
    private void updateDagJson(Workflow workflow, String newDagJson) {
        if (newDagJson == null) {
            return;  // 请求中没有DAG定义更新
        }
        if (!StringUtils.hasText(newDagJson)) {
            throw new IllegalArgumentException("DAG定义不能为空");
        }
        String dagJson = newDagJson.trim();
        WorkflowDAG dag = parseDAG(dagJson);
        validateDAG(dag);
        workflow.setDagJson(dagJson);
    }

    /**
     * 更新简单的字符串字段（带自动trim）
     */
    private void updateSimpleStringField(String newValue, java.util.function.Consumer<String> setter) {
        if (newValue != null) {
            if (StringUtils.hasText(newValue)) {
                setter.accept(newValue.trim());
            } else {
                setter.accept(null);
            }
        }
    }
    
    @Override
    @Transactional
    @RequireWorkflowPermission(
        roles = {"OWNER", "ADMIN", "MEMBER"},
        message = "当前角色无创建工作流权限",
        validateProject = true
    )
    public Long createWorkflow(WorkflowCreateRequest request) {
        log.info("创建工作流 workflowName={}", request.getWorkflowName());

        Long userId = WorkflowSecurityContextHolder.require().principal().userId();

        WorkflowDAG dag = parseDAG(request.getDagJson());
        validateDAG(dag);

        Workflow workflow = new Workflow();
        BeanUtils.copyProperties(request, workflow);

        String workflowCode;
        if (StringUtils.hasText(request.getWorkflowCode())) {
            workflowCode = request.getWorkflowCode().trim();
            Long dup = workflowMapper.selectCount(
                    new LambdaQueryWrapper<Workflow>()
                            .eq(Workflow::getProjectId, request.getProjectId())
                            .eq(Workflow::getWorkflowCode, workflowCode));
            if (dup != null && dup > 0) {
                throw new IllegalArgumentException("工作流编码已存在");
            }
        } else {
            workflowCode = "wf_" + UUID.randomUUID().toString().replace("-", "");
        }
        workflow.setWorkflowCode(workflowCode);

        if (!StringUtils.hasText(workflow.getScheduleType())) {
            workflow.setScheduleType("MANUAL");
        } else {
            workflow.setScheduleType(workflow.getScheduleType().trim());
        }
        if (StringUtils.hasText(workflow.getCronExpression())) {
            workflow.setCronExpression(workflow.getCronExpression().trim());
        } else {
            workflow.setCronExpression(null);
        }
        if (workflow.getAlertOnFailure() == null) {
            workflow.setAlertOnFailure(0);
        }
        if (workflow.getTimeoutSeconds() == null) {
            workflow.setTimeoutSeconds(7200);
        }
        workflow.setStatus(1);
        workflow.setVersion(0);
        workflow.setCreatorUserId(userId);

        int inserted = workflowMapper.insert(workflow);
        if (inserted != 1 || workflow.getId() == null) {
            throw new IllegalStateException("创建工作流失败");
        }
        log.info("创建工作流成功 id={}", workflow.getId());
        return workflow.getId();
    }
    
    @Override
    @Transactional
    @RequireWorkflowPermission(
        roles = {"OWNER", "ADMIN", "MEMBER"},
        message = "当前角色无更新工作流权限"
    )
    public void updateWorkflow(Long id, WorkflowUpdateRequest request) {
        log.info("更新工作流 id={}", id);
        Workflow workflow = requireOwnedWorkflow(id);

        // 使用辅助方法简化字段更新逻辑
        updateSimpleStringField(request.getWorkflowName(), workflow::setWorkflowName);
        updateWorkflowCode(workflow, request.getWorkflowCode());
        updateSimpleStringField(request.getDescription(), workflow::setDescription);
        updateDagJson(workflow, request.getDagJson());
        
        // 处理 scheduleType：如果为空则设置为 MANUAL，否则 trim
        if (request.getScheduleType() != null) {
            workflow.setScheduleType(StringUtils.hasText(request.getScheduleType()) 
                ? request.getScheduleType().trim() : "MANUAL");
        }
        
        // 处理 cronExpression：如果为空则设置为 null，否则 trim
        if (request.getCronExpression() != null) {
            workflow.setCronExpression(StringUtils.hasText(request.getCronExpression())
                ? request.getCronExpression().trim() : null);
        }
        
        // 处理其他简单字段
        if (request.getTimeoutSeconds() != null) {
            workflow.setTimeoutSeconds(request.getTimeoutSeconds());
        }
        if (request.getAlertOnFailure() != null) {
            workflow.setAlertOnFailure(request.getAlertOnFailure());
        }
        
        // 状态有效性检查
        if (request.getStatus() != null) {
            if (request.getStatus() != 0 && request.getStatus() != 1) {
                throw new IllegalArgumentException("状态值无效");
            }
            workflow.setStatus(request.getStatus());
        }

        int updated = workflowMapper.updateById(workflow);
        if (updated != 1) {
            throw new IllegalStateException("更新工作流失败，可能版本冲突或记录不存在");
        }
        workflowExecutionService.invalidatePlanCache(id);
    }
    
    @Override
    @Transactional
    @RequireWorkflowPermission(
        roles = {"OWNER", "ADMIN"},
        message = "当前角色无删除工作流权限"
    )
    public void deleteWorkflow(Long id) {
        log.info("删除工作流 id={}", id);
        Workflow workflow = requireOwnedWorkflow(id);
        int deleted = workflowMapper.deleteById(workflow.getId());
        if (deleted != 1) {
            throw new IllegalStateException("删除工作流失败");
        }
    }
    
    @Override
    @RequireWorkflowPermission(
        roles = {"OWNER", "ADMIN", "MEMBER", "GUEST"},
        message = "当前角色无查看工作流权限"
    )
    public Workflow getById(Long id) {
        Long tenantId = WorkflowSecurityContextHolder.require().principal().tenantId();

        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            return null;
        }
        
        Project project = validateProjectBelongsToTenant(workflow.getProjectId(), tenantId);
        if (project == null) {
            return null;
        }
        return workflow;
    }

    /**
     * 加载工作流并校验：记录存在且所属项目在当前租户下。
     */
    private Workflow requireOwnedWorkflow(Long workflowId) {
        Long tenantId = WorkflowSecurityContextHolder.require().principal().tenantId();

        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("工作流不存在");
        }
        
        Project project = validateProjectBelongsToTenant(workflow.getProjectId(), tenantId);
        if (project == null) {
            throw new IllegalArgumentException("工作流不存在");
        }
        return workflow;
    }
    
    @Override
    public WorkflowVO toVO(Workflow workflow) {
        if (workflow == null) {
            return null;
        }
        WorkflowVO vo = new WorkflowVO();
        BeanUtils.copyProperties(workflow, vo);

        String dagJson = workflow.getDagJson();
        if (dagJson != null && !dagJson.isBlank()) {
            try {
                WorkflowDAG dag = objectMapper.readValue(dagJson, WorkflowDAG.class);
                vo.setDag(dag);
                vo.setTaskCount(dag.getTasks() != null ? dag.getTasks().size() : 0);
            } catch (Exception e) {
                log.warn("工作流 dagJson 解析失败 id={}", workflow.getId(), e);
                vo.setDag(null);
                vo.setTaskCount(0);
            }
        } else {
            vo.setDag(null);
            vo.setTaskCount(0);
        }
        return vo;
    }
    
    @Override
    @RequireWorkflowPermission(
        roles = {"OWNER", "ADMIN", "MEMBER", "GUEST"},
        message = "当前角色无查看工作流列表权限"
    )
    public List<WorkflowVO> listWorkflows(Long projectId, Integer status) {
        log.info("查询工作流列表 projectId={} status={}", projectId, status);

//        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
//                .eq(Workflow::getProjectId, projectId)
//                .eq(status != null, Workflow::getStatus, status)
//                .orderByDesc(Workflow::getCreatedAt);
//        List<Workflow> workflows = workflowMapper.selectList(wrapper);
        List<Workflow> workflows = workflowMapper.selectByProjectIdAndStatus(projectId,status);
        return workflows.stream().map(this::toVO).collect(Collectors.toList());
    }

    // ==================== DAG解析与验证 =====================
    @Override
    public WorkflowDAG parseDAG(String dagJson) {
        try {
            // 解析JSON为WorkflowDAG对象
            WorkflowDAG dag = objectMapper.readValue(dagJson, WorkflowDAG.class);
            // 校验必填字段
            if (dag.getTasks() == null || dag.getTasks().isEmpty()) {
                throw new IllegalArgumentException("工作流至少包含一个任务");
            }
            return dag;
        } catch (Exception e) {
            log.error("解析DAG JSON失败", e);
            throw new IllegalArgumentException("DAG JSON格式错误: " + e.getMessage());
        }
    }
    
    @Override
    public void validateDAG(WorkflowDAG dag) {
        // 1. 验证节点名称唯一性
        Set<String> nodeNames = new HashSet<>();
        for (WorkflowTask task : dag.getTasks()){
            if (!nodeNames.add(task.getNodeName())){
                throw new IllegalArgumentException("节点名称重复: " + task.getNodeName());
            }
        }
        
        // 2. 验证引用的 Task 实体存在（查询 task 表）
        Set<Long> taskIds = dag.getTasks().stream()
                .map(WorkflowTask::getTaskId)
                        .collect(Collectors.toSet());
        List<Task> tasks = taskMapper.selectByIds(taskIds);
        if (tasks.size() != taskIds.size()){
            throw new IllegalArgumentException("引用的任务不存在");
        }

        // 3. 验证依赖引用的节点存在
        Set<String> nodeNameSet = dag.getTasks().stream()
                .map(WorkflowTask::getNodeName)
                        .collect(Collectors.toSet());
        List<WorkflowDependency> dependencies =
                dag.getDependencies() != null ? dag.getDependencies() : Collections.emptyList();
        for (WorkflowDependency dep : dependencies) {
            if (!nodeNameSet.contains(dep.getFrom())) {
                throw new IllegalArgumentException("依赖的上游节点不存在: " + dep.getFrom());
            }
            if (!nodeNameSet.contains(dep.getTo())) {
                throw new IllegalArgumentException("依赖的下游节点不存在: " + dep.getTo());
            }
        }

        workflowConditionSyntaxValidator.validateAll(
                dependencies.stream().map(WorkflowDependency::getCondition).collect(Collectors.toList()));

        // 4. 调用detectCycle()检测循环依赖
        detectCycle(dag);

        log.info("DAG验证通过 taskCount={} dependencyCount={}", 
                dag.getTasks() != null ? dag.getTasks().size() : 0,
                dependencies.size());
    }
    
    /**
     * 检测循环依赖（DFS + 三色标记）
     * 
     * @param dag DAG对象
     * @throws IllegalArgumentException 如果存在循环依赖
     */
    private void detectCycle(WorkflowDAG dag) {
        // 1. 构建邻接表（使用 nodeName 作为 key）
        Map<String, List<String>> graph = new HashMap<>();
        for (WorkflowTask task : dag.getTasks()){
            graph.put(task.getNodeName(), new ArrayList<>());
        }
        List<WorkflowDependency> dependencies =
                dag.getDependencies() != null ? dag.getDependencies() : Collections.emptyList();
        for (WorkflowDependency dep : dependencies) {
            graph.get(dep.getFrom()).add(dep.getTo());
        }

        // 2. 初始化三色标记（0=白色，1=灰色，2=黑色）
        Map<String, Integer> color = new HashMap<>();
        for (String nodeName : graph.keySet()) {
            color.put(nodeName, 0);
        }
        
        // 3. DFS检测环
        for (String nodeName : graph.keySet()){
            if (color.get(nodeName) == 0){
                if (dfsHasCycle(nodeName, graph, color, new ArrayList<>())){
                    throw new IllegalArgumentException("检测到循环依赖，DAG无效");
                }
            }
        }
        log.info("循环依赖检测通过");
    }
    
    /**
     * DFS递归检测环
     * 
     * @param node 当前节点
     * @param graph 邻接表
     * @param color 节点颜色
     * @param path 访问路径（用于记录环）
     * @return 是否存在环
     */
    private boolean dfsHasCycle(
            String node,
            Map<String, List<String>> graph,
            Map<String, Integer> color,
            List<String> path) {
        
        // DFS环检测算法
        // 1. 标记当前节点为灰色
        color.put(node, 1);
        path.add(node);
        
        // 2. 访问所有邻居
        for (String neighbor : graph.get(node)) {
            int neighborColor = color.get(neighbor);
            // 3. 如果邻居是灰色，说明存在环
            if(neighborColor == 1){
                path.add(neighbor);
                log.error("检测到循环依赖: {}", String.join(" → ", path));
                return true;
            }
            // 4. 如果邻居是白色，递归访问
            if(neighborColor == 0){
                if(dfsHasCycle(neighbor, graph, color, path)){
                    return true;
                }
            }
        }
        // 5. 标记当前节点为黑色
        color.put(node, 2);
        path.removeLast();
        
        return false;
    }
}
