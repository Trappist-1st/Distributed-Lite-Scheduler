package com.imperium.distributed_lite_scheduler_v1.service.workflow.stream;

/**
 * 任务完成 Redis Stream 常量（Spring Data Redis）。
 */
public final class TaskCompletionStreamConstants {

    public static final String STREAM_KEY = "task-completion";
    public static final String CONSUMER_GROUP = "dag-engine-group";
    public static final String CONSUMER_NAME_PREFIX = "dag-engine-";

    private TaskCompletionStreamConstants() {
    }
}
