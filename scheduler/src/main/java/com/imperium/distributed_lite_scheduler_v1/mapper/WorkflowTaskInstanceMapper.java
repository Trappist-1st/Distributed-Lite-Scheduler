package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.WorkflowTaskInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 工作流任务实例Mapper接口
 * 
 * 提供工作流任务实例的数据库操作方法
 */
@Mapper
public interface WorkflowTaskInstanceMapper extends BaseMapper<WorkflowTaskInstance> {
    
    /**
     * 根据工作流实例ID查询所有任务实例
     * 
     * @param workflowInstanceId 工作流实例ID
     * @return 任务实例列表
     */
    @Select("SELECT * FROM workflow_task_instance WHERE workflow_instance_id = #{workflowInstanceId}")
    List<WorkflowTaskInstance> selectByInstanceId(@Param("workflowInstanceId") Long workflowInstanceId);
    
    /**
     * 根据工作流实例ID和层级索引查询任务实例
     * 
     * @param workflowInstanceId 工作流实例ID
     * @param layerIndex 层级索引
     * @return 任务实例列表
     */
    @Select("SELECT * FROM workflow_task_instance WHERE workflow_instance_id = #{workflowInstanceId} AND layer_index = #{layerIndex}")
    List<WorkflowTaskInstance> selectByInstanceIdAndLayer(
            @Param("workflowInstanceId") Long workflowInstanceId,
            @Param("layerIndex") Integer layerIndex
    );
    
    /**
     * 查询工作流实例中正在运行的任务
     * 
     * @param workflowInstanceId 工作流实例ID
     * @return 运行中的任务实例列表
     */
    @Select("SELECT * FROM workflow_task_instance WHERE workflow_instance_id = #{workflowInstanceId} AND status IN ('PENDING', 'RUNNING')")
    List<WorkflowTaskInstance> selectRunningTasksByInstance(@Param("workflowInstanceId") Long workflowInstanceId);
    
    /**
     * 根据工作流实例ID和任务名称查询任务实例
     * 
     * @param workflowInstanceId 工作流实例ID
     * @param taskName 任务名称
     * @return 任务实例
     */
    @Select("SELECT * FROM workflow_task_instance WHERE workflow_instance_id = #{workflowInstanceId} AND task_name = #{taskName}")
    WorkflowTaskInstance selectByInstanceIdAndTaskName(
            @Param("workflowInstanceId") Long workflowInstanceId,
            @Param("taskName") String taskName
    );
    
    /**
     * 批量更新任务状态
     * 
     * @param taskInstanceIds 任务实例ID列表
     * @param status 新状态
     * @return 更新行数
     */
    @Update("<script>UPDATE workflow_task_instance SET status = #{status} WHERE id IN <foreach collection='taskInstanceIds' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    int batchUpdateStatus(
            @Param("taskInstanceIds") List<Long> taskInstanceIds,
            @Param("status") String status
    );
    
    /**
     * 查询所有RUNNING状态的工作流任务实例
     * 用于任务完成事件监听器扫描
     * 
     * @return RUNNING状态的任务实例列表
     */
    @Select("SELECT * FROM workflow_task_instance WHERE status = 'RUNNING' AND task_instance_id IS NOT NULL")
    List<WorkflowTaskInstance> selectRunningTasks();
    
    /**
     * 根据任务实例ID查询关联的工作流任务实例
     * 
     * 用于事件驱动模式：当任务实例完成时，通过此方法查找关联的工作流任务
     * 
     * @param taskInstanceId 任务实例ID
     * @return 工作流任务实例列表（可能有多个，如果同一个任务被多个工作流引用）
     */
    @Select("SELECT * FROM workflow_task_instance WHERE task_instance_id = #{taskInstanceId}")
    List<WorkflowTaskInstance> selectByTaskInstanceId(@Param("taskInstanceId") Long taskInstanceId);
}
