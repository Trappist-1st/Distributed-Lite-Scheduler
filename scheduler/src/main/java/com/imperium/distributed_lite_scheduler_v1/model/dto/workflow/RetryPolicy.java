package com.imperium.distributed_lite_scheduler_v1.model.dto.workflow;

import lombok.Data;

/**
 * 任务重试策略
 * 
 * 定义任务失败后的重试行为，用于覆盖 Task 实体中的默认重试配置
 * 
 * 对应 Task 实体字段：
 * - maxRetries -> Task.retryTimes
 * - retryInterval -> Task.retryInterval
 * 
 * @author UNSC
 * @since 2026-05-03
 */
@Data
public class RetryPolicy {
    
    /**
     * 最大重试次数
     * 对应 Task.retryTimes
     * 
     * - 0 表示不重试
     * - null 表示使用 Task 中的默认值
     * - > 0 表示最多重试的次数
     */
    private Integer maxRetries;
    
    /**
     * 重试间隔（秒）
     * 对应 Task.retryInterval
     * 
     * 每次重试之间的等待时间
     * null 表示使用 Task 中的默认值
     */
    private Integer retryInterval;
    
    /**
     * 退避倍数（可选，高级特性）
     * 
     * 每次重试的等待时间 = retryInterval * (backoffMultiplier ^ 当前重试次数)
     * 例如：retryInterval=60, backoffMultiplier=2.0
     *   第1次重试等待: 60 * 2^0 = 60秒
     *   第2次重试等待: 60 * 2^1 = 120秒
     *   第3次重试等待: 60 * 2^2 = 240秒
     * 
     * 如果为 null 或 1.0，则使用固定间隔
     */
    private Double backoffMultiplier;
    
    /**
     * 最大重试间隔（秒，可选）
     * 
     * 当使用 backoffMultiplier 时，限制重试间隔的上限
     * 防止指数退避导致等待时间过长
     * 
     * 例如：maxRetryInterval=3600 表示最多等待1小时
     */
    private Integer maxRetryInterval;
}
