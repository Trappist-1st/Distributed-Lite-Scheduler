package com.imperium.distributed_lite_scheduler_v1.model.dto;

import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import lombok.Data;

/**
 * 带优先级计算结果的任务视图（P3-3）。
 */
@Data
public class TaskWithPriority {
    private TaskInstance task;
    private Double effectivePriority;
    private Long waitingMinutes;
    private Integer priorityBonus;
}
