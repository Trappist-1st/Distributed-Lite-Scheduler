package com.imperium.distributed_lite_scheduler_v1.constant;

import lombok.Getter;

/**
 * 任务实例状态（调度器 task_instance 与工作流 workflow_task_instance 持久化字段统一使用此枚举对应的 code）。
 *
 * <p>状态流转（调度侧）：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED / TIMEOUT；
 * 工作流侧任务还可能为 SKIPPED。
 */
@Getter
public enum TaskInstanceStatus {

    PENDING("PENDING", "等待执行"),
    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    SKIPPED("SKIPPED", "跳过"),
    CANCELLED("CANCELLED", "已取消"),
    TIMEOUT("TIMEOUT", "超时");

    private final String code;
    private final String description;

    TaskInstanceStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 与持久化字段中的字符串是否一致（区分大小写，与库中存值一致）。
     */
    public boolean matches(String stored) {
        return code.equals(stored);
    }

    /**
     * 从 code 解析；兼容历史小写存值（如 {@code pending}）。
     */
    public static TaskInstanceStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Task instance status code must not be blank");
        }
        String normalized = code.trim();
        for (TaskInstanceStatus status : values()) {
            if (status.code.equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task instance status code: " + code);
    }

    /**
     * 是否已结束（含 SKIPPED，用于工作流任务层是否全部落定等判断）。
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED || this == CANCELLED || this == TIMEOUT;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED;
    }
}
