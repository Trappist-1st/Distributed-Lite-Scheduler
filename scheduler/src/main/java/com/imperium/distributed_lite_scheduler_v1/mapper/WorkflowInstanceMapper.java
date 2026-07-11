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
     * 查询所有 RUNNING 状态的工作流实例，供 ReconciliationWorker 扫描卡死工作流使用。
     */
    @Select("SELECT * FROM workflow_instance WHERE status = 'RUNNING' ORDER BY created_at ASC LIMIT #{limit}")
    List<WorkflowInstance> selectRunningInstances(@Param("limit") int limit);
}
