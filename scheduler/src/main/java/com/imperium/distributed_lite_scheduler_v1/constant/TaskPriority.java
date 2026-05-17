package com.imperium.distributed_lite_scheduler_v1.constant;

/**
 * 任务优先级定义（P3-3）。
 */
public enum TaskPriority {
    CRITICAL(10, "紧急"),
    HIGH(8, "高"),
    MEDIUM(5, "中"),
    LOW(3, "低"),
    MINIMAL(1, "最低");

    private final int value;
    private final String description;

    TaskPriority(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}
