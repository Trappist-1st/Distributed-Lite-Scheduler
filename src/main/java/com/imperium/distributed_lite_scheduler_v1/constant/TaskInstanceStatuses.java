package com.imperium.distributed_lite_scheduler_v1.constant;

import java.util.Set;

/**
 * task_instance 状态常量，避免魔法字符串分散在业务代码中。
 */
public final class TaskInstanceStatuses {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String TIMEOUT = "TIMEOUT";

    public static final Set<String> TERMINAL_STATUSES = Set.of(
            SUCCESS, FAILED, CANCELLED, TIMEOUT
    );

    private TaskInstanceStatuses() {
    }
}
