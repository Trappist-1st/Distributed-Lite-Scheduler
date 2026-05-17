package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行日志实体类
 * 对应表：execution_log
 */
@Data
@TableName("execution_log")
public class ExecutionLog {
    
    /**
     * 日志ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 任务实例ID
     */
    private Long taskInstanceId;
    
    /**
     * 日志级别：DEBUG/INFO/WARN/ERROR
     */
    private String logLevel;
    
    /**
     * 日志内容
     */
    private String logContent;
    
    /**
     * 日志时间
     */
    private LocalDateTime logTime;
    
    /**
     * 日志来源：STDOUT/STDERR/SYSTEM
     */
    private String source;
    
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
