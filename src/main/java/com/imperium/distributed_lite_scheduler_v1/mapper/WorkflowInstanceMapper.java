package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流实例Mapper接口
 * 
 * 提供工作流实例的数据库操作方法
 */
@Mapper
public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {
    
    /**
     * 增加已完成任务数
     * 
     * @param instanceId 工作流实例ID
     * @return 更新行数
     */
    @Update("UPDATE workflow_instance SET success_tasks = success_tasks + 1 WHERE id = #{instanceId}")
    int incrementCompletedTasks(@Param("instanceId") Long instanceId);
    
    /**
     * 增加失败任务数
     * 
     * @param instanceId 工作流实例ID
     * @return 更新行数
     */
    @Update("UPDATE workflow_instance SET failed_tasks = failed_tasks + 1 WHERE id = #{instanceId}")
    int incrementFailedTasks(@Param("instanceId") Long instanceId);
    
    /**
     * 根据工作流ID查询实例列表
     * 
     * @param workflowId 工作流ID
     * @return 实例列表
     */
    @Select("SELECT * FROM workflow_instance WHERE workflow_id = #{workflowId}")
    List<WorkflowInstance> selectByWorkflowId(@Param("workflowId") Long workflowId);
    
    /**
     * 根据状态查询实例列表
     * 
     * @param status 状态
     * @return 实例列表
     */
    @Select("SELECT * FROM workflow_instance WHERE status = #{status}")
    List<WorkflowInstance> selectByStatus(@Param("status") String status);
    
    /**
     * 查询正在运行的实例数量
     * 
     * @param workflowId 工作流ID
     * @return 运行中的实例数量
     */
    @Select("SELECT COUNT(*) FROM workflow_instance WHERE workflow_id = #{workflowId} AND status = 'RUNNING'")
    int countRunningInstances(@Param("workflowId") Long workflowId);
}
