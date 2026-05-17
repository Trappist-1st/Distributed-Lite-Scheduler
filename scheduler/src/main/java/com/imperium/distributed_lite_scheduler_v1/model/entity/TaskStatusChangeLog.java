package com.imperium.distributed_lite_scheduler_v1.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_status_change_log")
public class TaskStatusChangeLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long taskInstanceId;

    private String fromStatus;

    private String toStatus;

    private String triggerSource;

    private String reason;

    private Long operatorUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

