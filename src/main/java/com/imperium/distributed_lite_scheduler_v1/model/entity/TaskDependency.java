package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务依赖实体类
 * 对应表：task_dependency
 */
@Data
@TableName("task_dependency")
public class TaskDependency {
    
    /**
     * 依赖ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 父任务ID（被依赖的任务）
     */
    private Long parentTaskId;
    
    /**
     * 子任务ID（依赖其他任务的任务）
     */
    private Long childTaskId;
    
    /**
     * 依赖类型：SUCCESS/FAILED/FINISHED
     */
    private String dependencyType;
    
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
