package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 工作流Mapper
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {
    
    /**
     * 根据项目ID查询工作流列表
     * 
     * @param projectId 项目ID
     * @return 工作流列表
     */
    @Select("SELECT * FROM workflow WHERE project_id = #{projectId} ORDER BY created_at DESC")
    List<Workflow> selectByProjectId(@Param("projectId") Long projectId);
    
    /**
     * 根据项目ID和状态查询工作流列表
     * 
     * @param projectId 项目ID
     * @param status 状态
     * @return 工作流列表
     */
    @Select("SELECT * FROM workflow WHERE project_id = #{projectId} AND status = #{status} ORDER BY created_at DESC")
    List<Workflow> selectByProjectIdAndStatus(@Param("projectId") Long projectId, 
                                              @Param("status") Integer status);
}
