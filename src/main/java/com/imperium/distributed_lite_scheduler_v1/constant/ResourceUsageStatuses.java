package com.imperium.distributed_lite_scheduler_v1.constant;

import java.util.Set;

/**
 * resource_usage 状态常量与集合（释放、重复预留判定等共用，避免魔法字符串分叉）。
 */
public final class ResourceUsageStatuses {

    public static final String RESERVED = "RESERVED";
    public static final String RUNNING = "RUNNING";
    public static final String RELEASED = "RELEASED";
    public static final String FAILED = "FAILED";
    /** 预留流水扩展态：与任务取消语义对齐时可写入 */
    public static final String CANCELLED = "CANCELLED";

    /**
     * 仍可执行 release（回补槽位/配额）的流水状态。
     */
    public static final Set<String> RELEASABLE_STATUSES = Set.of(
            RESERVED, RUNNING, FAILED, CANCELLED);

    /**
     * 存在任一条此类流水时，禁止同一 task_instance 再次 reserve，必须先 release（含 FAILED 回收）。
     */
    public static final Set<String> BLOCK_NEW_RESERVE_UNTIL_RELEASED = RELEASABLE_STATUSES;

    private ResourceUsageStatuses() {
    }
}
